package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;

/** Context-owned world upload, asynchronous camera ordering and immutable completed-frame endpoint. */
public final class GlWorldView implements AutoCloseable {
    public enum Phase { IDLE, WAITING_CPU, UPLOADING, WAITING_VIEW, SORTING, FRAME_READY, DELIVERED, FAILED, CLOSED }
    public record Status(long worldRevision,long viewRevision,Phase phase,Optional<WorldSnapshot.Source> source,List<WorldProblem> problems,Optional<GlFailure.Code> code,String reason) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(source);problems=List.copyOf(problems);Objects.requireNonNull(code);Objects.requireNonNull(reason); }
    }
    private final GlRenderer renderer;private final GlDevice device;private final GlUploadQueue uploads;private final GlFrameQueue frames;private final WorldOrderWorker ordering;private final AtlasFrameWorker animationWorker;
    private GlScene scene;private volatile GlWorldAnimation animation;private GlDrawPlan order;private GlCamera camera;private WorldClip clip=WorldClip.NONE;private int width,height;private long worldRevision,viewRevision,sortRevision,atlasTick;private double atlasPartialTick;private boolean sourceReady,atlasClockSet,dirty,closed,disposed;
    private volatile Status status=new Status(0,0,Phase.IDLE,Optional.empty(),List.of(),Optional.empty(),"");
    public GlWorldView(GlRenderer renderer) { this(renderer,1024L*1024*1024); }
    public GlWorldView(GlRenderer renderer,long animationWorkingBytes) {
        this.renderer=Objects.requireNonNull(renderer);device=renderer.device;device.live(renderer.allocation);
        var nextOrdering=new WorldOrderWorker(1024L*1024*1024,device.memory().orElse(null));AtlasFrameWorker nextAnimation=null;
        try {
            nextAnimation=new AtlasFrameWorker(animationWorkingBytes,device.memory().orElse(null));uploads=new GlUploadQueue(renderer);frames=new GlFrameQueue(renderer);ordering=nextOrdering;animationWorker=nextAnimation;
        }catch(RuntimeException|Error failure) { nextOrdering.close();if(nextAnimation!=null)nextAnimation.close();throw failure; }
    }
    public Status status() { return status; }
    public GlUploadQueue.Status uploadStatus() { return uploads.status(); }
    public WorldOrderWorker.Status orderStatus() { return ordering.status(); }
    public long orderReservedBytes() { return ordering.reservedBytes(); }
    public AtlasFrameWorker.Status animationWorkerStatus() { return animationWorker.status(); }
    public long animationReservedBytes() { return animationWorker.reservedBytes(); }
    public Optional<GlWorldAnimation.Status> animationStatus() { return Optional.ofNullable(animation).map(GlWorldAnimation::status); }
    public Optional<GlAtlasQueue.Status> animationUploadStatus() { return Optional.ofNullable(animation).map(GlWorldAnimation::uploadStatus); }
    /** Display-only atlas clock; no generation, model bake, camera-order invalidation or internal timer. */
    public void clock(long tick,double partialTick) {
        current();if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid world atlas clock");
        atlasTick=tick;atlasPartialTick=partialTick;atlasClockSet=true;if(sourceReady&&animation!=null)animation.clock(tick,partialTick);
    }
    private void current() { device.current();if(closed)throw new GlFailure(GlFailure.Code.STATE,"World view is closed"); }
    private void dimensions(int width,int height) { GlTarget.validate(device,width,height); }
    private long nextView() { if(viewRevision==Long.MAX_VALUE)throw new GlFailure(GlFailure.Code.LIMIT,"View revision counter exhausted");return viewRevision+1; }
    private void state(Phase phase) { status=new Status(worldRevision,viewRevision,phase,status.source(),List.of(),Optional.empty(),""); }
    /** A retained cleanup owner is not proof that this revision completed upload. */
    private Status displayStatus(long next) {
        boolean sourceFailure=!sourceReady&&status.phase()==Phase.FAILED;
        return new Status(worldRevision,next,sourceReady?Phase.WAITING_VIEW:status.phase(),status.source(),sourceFailure?status.problems():List.of(),sourceFailure?status.code():Optional.empty(),sourceFailure?status.reason():"");
    }
    private void failed(Throwable error) {
        String reason=Objects.toString(error.getMessage(),error.getClass().getSimpleName());if(reason.length()>8192)reason=reason.substring(0,8192);
        var code=error instanceof GlFailure failure?failure.code():error instanceof OutOfMemoryError?GlFailure.Code.MEMORY:GlFailure.Code.STATE;
        status=new Status(worldRevision,viewRevision,Phase.FAILED,status.source(),List.of(),Optional.of(code),reason);
    }
    /** Notify immediately when native input changes, before the replacement CPU scene exists. */
    public boolean invalidate(long nextWorldRevision) {
        current();if(nextWorldRevision<1)throw new IllegalArgumentException("Invalid world revision");if(nextWorldRevision<=worldRevision)return false;long nextView=nextView();
        var waiting=new Status(nextWorldRevision,nextView,Phase.WAITING_CPU,Optional.empty(),List.of(),Optional.empty(),"");
        worldRevision=nextWorldRevision;viewRevision=nextView;sourceReady=false;dirty=false;sortRevision=0;status=waiting;
        try { ordering.invalidate();frames.invalidate(viewRevision);uploads.invalidate(worldRevision);releaseScene();return true; }
        catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    public boolean submit(WorldRenderWorker.Completion completion) { Objects.requireNonNull(completion);return submit(completion.revision(),completion.owner(),completion.tint().orElse(null),null); }
    public boolean submit(long revision,WorldScene.Result owner) { return submit(revision,owner,null,null); }
    /** Consumes a fresh CPU scene and admitted lighting input, preserving source charge through upload admission. */
    public boolean submitLit(long revision,WorldScene.Result owner,WorldTint tint,RenderInput<WorldLighting> input) {
        Objects.requireNonNull(owner);Objects.requireNonNull(input);boolean handedOff=false;
        try {
            current();if(device.ownsCpu(owner)) { handedOff=true;return false; }
            try(var source=input.retain()) {
                var memory=device.memory().orElse(null);if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Lighting requires the same managed world-view coordinator");
                var lighting=source.value();if(lighting.requiresPlaceholder()||lighting.scene()!=owner.scene().orElseThrow())throw new GlFailure(GlFailure.Code.STALE,"Lighting belongs to another successful exact CPU scene");
                handedOff=true;return submit(revision,owner,tint,lighting);
            }
        }finally { input.close();if(!handedOff)owner.close(); }
    }
    /** Consumes fresh CPU scene owners on every outcome; optional native colors belong to this exact scene. */
    public boolean submit(long revision,WorldScene.Result owner,WorldTint tint,WorldLighting lighting) {
        Objects.requireNonNull(owner);boolean handedOff=false;
        try {
            current();if(device.ownsCpu(owner)) { handedOff=true;return false; }
            if(revision<1)throw new IllegalArgumentException("Invalid world revision");if(revision<worldRevision||revision==worldRevision&&status.phase()!=Phase.WAITING_CPU)return false;
            tint=GlUpload.validate(renderer,owner,tint,lighting);
            if(revision>worldRevision)invalidate(revision);
            status=new Status(worldRevision,viewRevision,Phase.UPLOADING,Optional.of(owner.source()),List.of(),Optional.empty(),"");
            handedOff=true;
            try { boolean accepted=uploads.submit(revision,owner,tint,lighting);if(!accepted)throw new GlFailure(GlFailure.Code.STATE,"World upload did not accept its current CPU scene");return true; }
            catch(RuntimeException|Error failure) { failed(failure);throw failure; }
        }
        finally { if(!handedOff)owner.close(); }
    }
    public WorldClip clip() { current();return clip; }
    /** Geometric display clipping preserves source meshes and the existing complete transparent order. */
    public long clip(WorldClip clip) {
        Objects.requireNonNull(clip);current();if(clip.equals(this.clip))return viewRevision;long next=nextView();var changed=displayStatus(next);
        this.clip=clip;viewRevision=next;dirty=true;status=changed;
        try { frames.invalidate(viewRevision);return viewRevision; }
        catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Display-only camera/size changes never regenerate or rebake the native world. */
    public long view(GlCamera camera,int width,int height) {
        Objects.requireNonNull(camera);current();
        dimensions(width,height);
        camera.matrix((double)width/height);if(camera.equals(this.camera)&&width==this.width&&height==this.height)return viewRevision;
        long next=nextView();var changed=displayStatus(next);boolean poseChanged=this.camera==null||!camera.drawView().equals(this.camera.drawView());this.camera=camera;this.width=width;this.height=height;viewRevision=next;dirty=true;status=changed;
        try { frames.invalidate(viewRevision);if(poseChanged)resetOrder();return viewRevision; }
        catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Fit complete emitted world/marker bounds; geometric clipping remains independently configured. */
    public long fit(int width,int height) { return fit(width,height,WorldClip.NONE); }
    /** Fit the conservative intersection of current source bounds and the current geometric clip. */
    public long fitClipped(int width,int height) { return fit(width,height,clip); }
    private long fit(int width,int height,WorldClip scope) {
        current();dimensions(width,height);if(!sourceReady||scene==null)throw new GlFailure(GlFailure.Code.STATE,"A complete current world upload is required before camera fitting");
        return view(GlOrbitCamera.fit(scene.cpuScene(),(double)width/height,scope).camera(),width,height);
    }
    /** Explicit redraw/retry after a view failure; keeps the same complete world and native data. */
    public long redraw() {
        current();if(!sourceReady||scene==null||camera==null)throw new GlFailure(GlFailure.Code.STATE,"A complete current world upload and camera are required to redraw");long next=nextView();var changed=displayStatus(next);viewRevision=next;dirty=true;status=changed;
        try { frames.invalidate(viewRevision);if(order==null)resetOrder();return viewRevision; }
        catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Reopen a failed current source upload; then submit a fresh retained CPU owner at the same world token. */
    public boolean retryUpload(long expectedWorldRevision) {
        current();if(expectedWorldRevision<1)throw new IllegalArgumentException("Invalid world revision");
        if(expectedWorldRevision!=worldRevision||sourceReady||status.phase()!=Phase.FAILED)return false;
        long next=nextView();var waiting=new Status(worldRevision,next,Phase.WAITING_CPU,status.source(),List.of(),Optional.empty(),"");
        viewRevision=next;dirty=false;sortRevision=0;
        try {
            ordering.invalidate();frames.invalidate(viewRevision);releaseScene();
            if(uploads.status().revision()<worldRevision)uploads.invalidate(worldRevision);
            if(!uploads.restart(worldRevision))throw new GlFailure(GlFailure.Code.STALE,"World upload queue no longer matches the retry revision");
            status=waiting;return true;
        }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    private void resetOrder() { ordering.invalidate();sortRevision=0;if(order!=null) { order.close();order=null; } }
    /** Upload steps are bounded; drawing a completed frame remains one synchronous context operation. */
    public Status advance(GlUploadBudget budget,long latestWorldRevision) {
        Objects.requireNonNull(budget);current();if(latestWorldRevision<1)throw new IllegalArgumentException("Invalid world revision");if(latestWorldRevision>worldRevision) { invalidate(latestWorldRevision);return status; }if(latestWorldRevision<worldRevision||status.phase()==Phase.FAILED)return status;
        try {
            boolean sceneUploadStepped=false;
            if(status.phase()==Phase.UPLOADING) {
                sceneUploadStepped=true;
                uploads.advance(budget,worldRevision);var completion=uploads.take(worldRevision);
                if(completion.isPresent()) { scene=completion.orElseThrow().scene();sourceReady=true;dirty=true;state(Phase.WAITING_VIEW); }
            }
            if(sourceReady&&scene!=null&&animation==null) { animation=new GlWorldAnimation(scene,worldRevision,animationWorker);if(atlasClockSet)animation.clock(atlasTick,atlasPartialTick); }
            // Full-scene and animated-atlas upload never each spend the same caller budget in one advance.
            if(sourceReady&&animation!=null&&!sceneUploadStepped&&animation.advance(budget)) {
                viewRevision=nextView();dirty=true;frames.invalidate(viewRevision);state(Phase.WAITING_VIEW);
            }
            if(!sourceReady||scene==null||camera==null||!dirty)return status;
            if(scene.translucentFaces>0&&order==null) {
                if(sortRevision==0)sortRevision=ordering.submit(scene.retainCpuScene(),camera.drawView());
                var completion=ordering.take(sortRevision);
                if(completion.isPresent())try(var sorted=completion.orElseThrow()) {
                    if(sorted.order().scene()!=scene.cpuScene()||!sorted.order().view().equals(camera.drawView()))throw new GlFailure(GlFailure.Code.STALE,"Camera sort no longer belongs to the current world/view");order=GlDrawPlan.create(scene,sorted.order());
                }
                else {
                    var sorting=ordering.status();if(sorting.phase()==WorldOrderWorker.Phase.FAILED) { status=new Status(worldRevision,viewRevision,Phase.FAILED,status.source(),sorting.problems(),Optional.empty(),"Translucent face ordering failed");return status; }
                    state(Phase.SORTING);return status;
                }
            }
            boolean rendered=frames.render(viewRevision,new GlFrameQueue.Request(scene,Optional.of(animation.frame()),Optional.ofNullable(order),camera,width,height,clip),()->false);
            if(!rendered)throw new GlFailure(GlFailure.Code.STATE,"Completed-frame queue rejected the current view revision");dirty=false;state(Phase.FRAME_READY);return status;
        }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Caller owns the returned image; it survives later world, camera, resize and failed replacement work. */
    public Optional<GlFrame> take(long expectedWorldRevision,long expectedViewRevision) {
        current();if(!sourceReady||expectedWorldRevision!=worldRevision||expectedViewRevision!=viewRevision||status.phase()!=Phase.FRAME_READY)return Optional.empty();var delivered=new Status(worldRevision,viewRevision,Phase.DELIVERED,status.source(),List.of(),Optional.empty(),"");var frame=frames.take(viewRevision);if(frame.isPresent())status=delivered;return frame;
    }
    private void releaseScene() {
        Throwable failure=null;
        if(animation!=null)try { animation.close();animation=null; }catch(RuntimeException|Error error) { failure=error; }
        else if(animationWorker.status().phase()!=AtlasFrameWorker.Phase.CLOSED)animationWorker.invalidate();
        if(order!=null)try { order.close();order=null; }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if(scene!=null)try { scene.close();scene=null; }catch(RuntimeException|Error error) { if(scene.allocation.retired||scene.allocation.released)scene=null;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    /** The renderer/device and all caller-transferred images remain caller-owned. Retry failed retirement. */
    @Override public void close() {
        if(disposed)return;
        device.current();closed=true;sourceReady=false;ordering.close();Throwable failure=null;
        try { frames.close(); }catch(RuntimeException|Error error) { failure=error; }
        try { uploads.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        try { releaseScene(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        animationWorker.close();
        status=new Status(worldRevision,viewRevision,Phase.CLOSED,Optional.empty(),List.of(),Optional.empty(),"");
        disposed=failure==null;
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
