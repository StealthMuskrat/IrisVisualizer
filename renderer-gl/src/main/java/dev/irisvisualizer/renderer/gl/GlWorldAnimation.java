package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;

/** Context-owned clock/CPU-plan/GPU-frame handoff. Borrows an exclusive worker reused across world replacements. */
public final class GlWorldAnimation implements AutoCloseable {
    public enum Phase { CURRENT, PREPARING, UPLOADING, FAILED, CLOSED }
    public record Status(long sceneRevision,long revision,Phase phase,long requestedTick,double requestedPartialTick,long displayedTick,double displayedPartialTick,List<ModelPlan.Problem> problems,Optional<GlFailure.Code> code,String reason) {
        public Status { Objects.requireNonNull(phase);problems=List.copyOf(problems);Objects.requireNonNull(code);Objects.requireNonNull(reason); }
    }
    private final GlScene scene;private final GlDevice device;private final AtlasFrameWorker worker;private final GlAtlasQueue queue;private final long sceneRevision;
    private GlAtlasFrame frame,retiring;private AtlasFrameWorker.Request request;private long revision,cpuRevision,tick;private double partialTick;
    private boolean clockDirty,playback,sampleReady=true,closed,disposed,invalidated;private volatile Status status;
    public GlWorldAnimation(GlScene scene,long sceneRevision,AtlasFrameWorker worker) {
        this.scene=Objects.requireNonNull(scene);device=scene.device;this.worker=Objects.requireNonNull(worker);scene.live();
        if(sceneRevision<1)throw new IllegalArgumentException("Invalid scene revision");worker.validateDestination(device.memory().orElse(null));
        this.sceneRevision=sceneRevision;queue=new GlAtlasQueue(scene,sceneRevision);
        try { frame=GlAtlasFrame.capture(scene);tick=frame.raster().tick();partialTick=frame.raster().partialTick();state(Phase.CURRENT,List.of(),null,""); }
        catch(RuntimeException|Error failure) { if(frame!=null)try { frame.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    public Status status() { return status; }
    /** Latest requested pixels have been observed and adopted, including clocks with unchanged pixels. */
    boolean settled() { current();return !clockDirty&&request==null&&cpuRevision==0&&status.phase()==Phase.CURRENT; }
    /** Playback may present a completed admitted sample while its latest clock is still catching up. */
    boolean presentable() { current();return status.phase()!=Phase.FAILED&&(settled()||playback&&sampleReady); }
    public GlAtlasQueue.Status uploadStatus() { return queue.status(); }
    private void current() { device.current();if(closed)throw new GlFailure(GlFailure.Code.STATE,"World animation is closed"); }
    private void state(Phase phase,List<ModelPlan.Problem> problems,GlFailure.Code code,String reason) {
        long displayedTick=frame==null?status.displayedTick():frame.raster().tick();double displayedPartial=frame==null?status.displayedPartialTick():frame.raster().partialTick();
        status=new Status(sceneRevision,revision,phase,tick,partialTick,displayedTick,displayedPartial,problems,Optional.ofNullable(code),reason);
    }
    private void failed(Throwable failure) {
        try {
            String reason=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());if(reason.length()>8192)reason=reason.substring(0,8192);
            state(Phase.FAILED,List.of(),failure instanceof GlFailure gpu?gpu.code():failure instanceof OutOfMemoryError?GlFailure.Code.MEMORY:GlFailure.Code.STATE,reason);
        }catch(RuntimeException|Error publication) { if(publication!=failure)failure.addSuppressed(publication); }
    }
    /** External clock, including seeks. A repeated failed clock explicitly retries; no internal timer runs. */
    public void clock(long tick,double partialTick) {
        current();if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid world atlas clock");
        if(!playback&&this.tick==tick&&this.partialTick==partialTick&&status.phase()!=Phase.FAILED)return;
        playback=false;sampleReady=false;this.tick=tick;this.partialTick=partialTick;clockDirty=true;state(status.phase(),status.problems(),status.code().orElse(null),status.reason());
    }
    /** Live clock: finish an admitted sample before sampling the latest clock; use clock for immediate seeks. */
    public void playbackClock(long tick,double partialTick) {
        current();if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid world atlas clock");
        if(playback&&this.tick==tick&&this.partialTick==partialTick&&status.phase()!=Phase.FAILED)return;
        if(!playback||status.phase()==Phase.FAILED)sampleReady=false;
        playback=true;this.tick=tick;this.partialTick=partialTick;clockDirty=true;state(status.phase(),status.problems(),status.code().orElse(null),status.reason());
    }
    private long next() { if(revision==Long.MAX_VALUE)throw new GlFailure(GlFailure.Code.LIMIT,"World animation revision counter exhausted");return revision+1; }
    private void releaseRetiring() {
        if(retiring!=null)try { retiring.close();retiring=null; }
        catch(RuntimeException|Error failure) { if(retiring.closedOrReleased())retiring=null;throw failure; }
    }
    private void observeClock() {
        var candidate=AtlasFrameWorker.Request.capture(frame.raster(),tick,partialTick);
        if(status.phase()!=Phase.FAILED&&candidate.samePixels(request)) { clockDirty=false;return; }
        if(candidate.changedSprites()==0) {
            if(request!=null) { long next=next();worker.invalidate();revision=next;queue.invalidate(sceneRevision,revision); }
            request=null;cpuRevision=0;clockDirty=false;sampleReady=true;state(Phase.CURRENT,List.of(),null,"");return;
        }
        long next=next(),cpu=worker.submit(candidate);revision=next;cpuRevision=cpu;request=candidate;clockDirty=false;
        queue.invalidate(sceneRevision,revision);state(Phase.PREPARING,List.of(),null,"");
    }
    /** At most one bounded atlas upload step; true means a complete new immutable atlas was adopted. */
    public boolean advance(GlUploadBudget budget) {
        Objects.requireNonNull(budget);current();
        try {
            // Live ticks occupy only the two scalar clock fields. Do not cancel/re-admit a running
            // CPU plan or partial GPU upload on every host tick. Explicit seeks still preempt it.
            releaseRetiring();if(clockDirty&&(!playback||request==null||status.phase()==Phase.FAILED))observeClock();if(status.phase()==Phase.FAILED||request==null)return false;
            if(cpuRevision!=0) {
                var completion=worker.take(cpuRevision);
                if(completion.isPresent())try(var prepared=completion.orElseThrow()) {
                    var plan=prepared.plan();
                    if(plan.expected()!=frame.raster())throw new GlFailure(GlFailure.Code.STALE,"Animation plan no longer targets the displayed atlas");
                    if(!queue.submit(sceneRevision,revision,frame,prepared.retainInput()))throw new GlFailure(GlFailure.Code.STALE,"Animation upload rejected its current frame revision");
                    cpuRevision=0;state(Phase.UPLOADING,List.of(),null,"");
                } else {
                    var cpu=worker.status();
                    if(cpu.revision()!=cpuRevision||cpu.phase()==AtlasFrameWorker.Phase.CLOSED)throw new GlFailure(GlFailure.Code.STALE,"Animation worker ownership changed before CPU plan adoption");
                    if(cpu.phase()==AtlasFrameWorker.Phase.FAILED)state(Phase.FAILED,cpu.problems(),null,"Atlas CPU preparation failed");return false;
                }
            }
            queue.advance(budget,sceneRevision,revision);var completion=queue.take(sceneRevision,revision,frame);
            if(completion.isEmpty())return false;
            retiring=frame;frame=completion.orElseThrow().frame();request=null;cpuRevision=0;clockDirty=true;
            releaseRetiring();sampleReady=true;state(Phase.CURRENT,List.of(),null,"");return true;
        }catch(RuntimeException|Error failure) { clockDirty=false;failed(failure);throw failure; }
    }
    /** Borrowed only for synchronous rendering on the owning context; never close this frame externally. */
    GlAtlasFrame frame() { current();frame.requireScene(scene);return frame; }
    /** Does not close the borrowed worker, base scene or independently completed images. */
    @Override public void close() {
        if(disposed)return;device.current();closed=true;Throwable failure=null;
        if(!invalidated)try { if(worker.status().phase()!=AtlasFrameWorker.Phase.CLOSED)worker.invalidate();invalidated=true;request=null;cpuRevision=0; }
        catch(RuntimeException|Error error) { failure=error; }
        try { queue.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        try { releaseRetiring(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if(frame!=null)try { frame.close();frame=null; }catch(RuntimeException|Error error) { if(frame.closedOrReleased())frame=null;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        // A failed frame close may already have cleared its context-affine state.
        try { status=new Status(sceneRevision,revision,Phase.CLOSED,tick,partialTick,status.displayedTick(),status.displayedPartialTick(),List.of(),Optional.empty(),""); }
        catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }disposed=failure==null;
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
