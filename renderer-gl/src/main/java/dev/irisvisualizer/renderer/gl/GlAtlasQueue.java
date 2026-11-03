package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;

/** Latest animation-plan adoption for one scene. The displayed frame always remains caller-owned. */
public final class GlAtlasQueue implements AutoCloseable {
    public enum Phase { IDLE, WAITING, UPDATING, READY, DELIVERED, STALE, FAILED, CLOSED }
    public record Status(long sceneRevision,long frameRevision,Phase phase,Optional<GlAtlasUpload.Progress> progress,Optional<GlFailure.Code> code,String reason) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(progress);Objects.requireNonNull(code);Objects.requireNonNull(reason); }
    }
    public record Completion(long sceneRevision,long frameRevision,GlAtlasFrame frame) implements AutoCloseable {
        public Completion { if(sceneRevision<1||frameRevision<1)throw new IllegalArgumentException("Invalid atlas revision");Objects.requireNonNull(frame); }
        @Override public void close() { frame.close(); }
    }
    private final GlScene scene;private final GlDevice device;private final long sceneRevision;
    private long frameRevision;private GlAtlasUpload active;private boolean closed,obsolete;
    private volatile Status status;
    public GlAtlasQueue(GlScene scene,long sceneRevision) {
        this.scene=Objects.requireNonNull(scene);device=scene.device;device.live(scene.allocation);if(sceneRevision<1)throw new IllegalArgumentException("Invalid scene revision");this.sceneRevision=sceneRevision;status=new Status(sceneRevision,0,Phase.IDLE,Optional.empty(),Optional.empty(),"");
    }
    public Status status() { return status; }
    private void current() { device.current();if(closed)throw new GlFailure(GlFailure.Code.STATE,"Atlas queue is closed"); }
    private Status state(Phase phase,GlFailure.Code code,String reason) { return new Status(sceneRevision,frameRevision,phase,active==null?Optional.empty():Optional.of(active.progress()),Optional.ofNullable(code),reason); }
    private void failure(Throwable failure) {
        var code=failure instanceof GlFailure gpu?gpu.code():failure instanceof OutOfMemoryError?GlFailure.Code.MEMORY:GlFailure.Code.STATE;String reason=failure.getMessage();if(reason==null)reason=failure.getClass().getSimpleName();if(reason.length()>8192)reason=reason.substring(0,8192);status=state(Phase.FAILED,code,reason);
    }
    private void releaseOwned() { if(active==null)return;try { active.close();active=null; }catch(RuntimeException|Error error) { if(!active.ownsResources())active=null;throw error; } }
    private boolean observe(long latestScene,long latestFrame) {
        if(latestScene<1||latestFrame<1)throw new IllegalArgumentException("Invalid atlas revision");
        if(latestScene<sceneRevision)return false;
        if(latestScene>sceneRevision) { obsolete=true;try { releaseOwned(); }finally { status=state(Phase.STALE,GlFailure.Code.STALE,"The GPU scene revision changed"); }return false; }
        if(obsolete)return false;
        if(latestFrame>frameRevision) { frameRevision=latestFrame;status=state(Phase.WAITING,null,"");try { releaseOwned(); }catch(RuntimeException|Error error) { failure(error);throw error; }status=state(Phase.WAITING,null,""); }
        return latestFrame==frameRevision;
    }
    public void invalidate(long latestScene,long latestFrame) { current();observe(latestScene,latestFrame); }
    public boolean submit(long latestScene,long latestFrame,GlAtlasFrame displayed,AtlasFramePlan plan) {
        Objects.requireNonNull(displayed);Objects.requireNonNull(plan);current();if(!observe(latestScene,latestFrame)||status.phase()!=Phase.WAITING)return false;
        try { displayed.requireScene(scene);active=GlAtlasUpload.begin(displayed,plan);status=state(Phase.UPDATING,null,"");return true; }
        catch(RuntimeException|Error error) { try { releaseOwned(); }catch(RuntimeException|Error cleanup) { if(cleanup!=error)error.addSuppressed(cleanup); }failure(error);throw error; }
    }
    public Status advance(GlUploadBudget budget,long latestScene,long latestFrame) {
        Objects.requireNonNull(budget);current();if(!observe(latestScene,latestFrame)||(status.phase()!=Phase.UPDATING&&status.phase()!=Phase.READY)||active==null)return status;
        try { device.live(scene.allocation);active.step(budget,()->false);status=state(active.ready()?Phase.READY:Phase.UPDATING,null,"");return status; }
        catch(RuntimeException|Error error) { try { releaseOwned(); }catch(RuntimeException|Error cleanup) { if(cleanup!=error)error.addSuppressed(cleanup); }failure(error);throw error; }
    }
    /** Caller adopts only against its still-displayed frame; neither stale nor failed work closes it. */
    public Optional<Completion> take(long latestScene,long latestFrame,GlAtlasFrame displayed) {
        Objects.requireNonNull(displayed);current();if(!observe(latestScene,latestFrame)||status.phase()!=Phase.READY||active==null||!active.ready())return Optional.empty();
        try {
            displayed.requireScene(scene);if(displayed.token()!=active.expectedToken()) { releaseOwned();status=state(Phase.STALE,GlFailure.Code.STALE,"The displayed atlas baseline changed");return Optional.empty(); }
            var result=Optional.of(new Completion(sceneRevision,frameRevision,active.readyFrame()));var delivered=new Status(sceneRevision,frameRevision,Phase.DELIVERED,status.progress(),Optional.empty(),"");
            active.take(displayed);active.close();active=null;status=delivered;return result;
        }catch(RuntimeException|Error error) { try { releaseOwned(); }catch(RuntimeException|Error cleanup) { if(cleanup!=error)error.addSuppressed(cleanup); }failure(error);throw error; }
    }
    @Override public void close() { if(closed&&active==null)return;closed=true;try { releaseOwned(); }finally { status=state(Phase.CLOSED,null,""); } }
}
