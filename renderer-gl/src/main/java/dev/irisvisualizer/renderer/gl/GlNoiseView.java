package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;

/** Connected scalar display preparation/upload/image endpoint. Native sampling and editor readiness stay external. */
public final class GlNoiseView implements AutoCloseable {
    public enum Phase { IDLE, WAITING_CPU, MESHING, RECOLORING, UPLOADING, WAITING_VIEW, FRAME_READY, DELIVERED, FAILED, CLOSED }
    public record Status(long dataRevision,long viewRevision,Phase phase,Optional<MapSamples.Context> context,List<WorldProblem> problems,Optional<GlFailure.Code> code,String reason) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(context);problems=List.copyOf(problems);Objects.requireNonNull(code);Objects.requireNonNull(reason); }
    }
    private final GlNoiseRenderer renderer;private final GlDevice device;private final NoiseSurfaceWorker worker;
    private GlNoiseUpload upload;private GlNoiseScene scene;private GlNoiseFrame completed;private GlCamera camera;private WorldClip clip=WorldClip.NONE;private NoiseSurface.Style latestStyle;private NoiseSurface.Limits latestLimits;private UUID latestVolume;private int width,height;private long dataRevision,viewRevision,cpuRevision;private boolean sourceReady,dirty,closed,disposed;
    private volatile Status status=new Status(0,0,Phase.IDLE,Optional.empty(),List.of(),Optional.empty(),"");
    public GlNoiseView(GlNoiseRenderer renderer) { this(renderer,1024L*1024*1024); }
    public GlNoiseView(GlNoiseRenderer renderer,long workerBytes) { this.renderer=Objects.requireNonNull(renderer);device=renderer.device;device.live(renderer.allocation);worker=new NoiseSurfaceWorker(workerBytes,device.memory().orElse(null)); }
    public Status status() { return status; }
    public NoiseSurfaceWorker.Status workerStatus() { return worker.status(); }
    public long reservedCpuBytes() { return worker.reservedBytes(); }
    public Optional<GlNoiseUpload.Progress> uploadProgress() { current();return upload==null?Optional.empty():Optional.of(upload.progress()); }
    private void current() { device.current();if(closed)throw new GlFailure(GlFailure.Code.STATE,"Scalar view is closed"); }
    private long nextData() { if(dataRevision==Long.MAX_VALUE)throw new GlFailure(GlFailure.Code.LIMIT,"Scalar data revision exhausted");return dataRevision+1; }
    private long nextView() { if(viewRevision==Long.MAX_VALUE)throw new GlFailure(GlFailure.Code.LIMIT,"Scalar view revision exhausted");return viewRevision+1; }
    private void state(Phase phase) { status=new Status(dataRevision,viewRevision,phase,status.context(),List.of(),Optional.empty(),""); }
    /** A scene still held for failed cleanup cannot serve the replacement data revision. */
    private Status displayStatus(long next) {
        boolean sourceFailure=!sourceReady&&status.phase()==Phase.FAILED;
        return new Status(dataRevision,next,sourceReady?Phase.WAITING_VIEW:status.phase(),status.context(),sourceFailure?status.problems():List.of(),sourceFailure?status.code():Optional.empty(),sourceFailure?status.reason():"");
    }
    private void failed(Throwable failure) {
        try {
            String reason=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());if(reason.length()>8192)reason=reason.substring(0,8192);
            status=new Status(dataRevision,viewRevision,Phase.FAILED,status.context(),List.of(),Optional.of(failure instanceof GlFailure gpu?gpu.code():failure instanceof OutOfMemoryError?GlFailure.Code.MEMORY:GlFailure.Code.STATE),reason);
        }catch(RuntimeException|Error publication) { if(publication!=failure)failure.addSuppressed(publication); }
    }
    /** Call when native inputs become obsolete, before replacement samples are ready. */
    public long invalidate() {
        current();long next=nextData(),view=nextView();var waiting=new Status(next,view,Phase.WAITING_CPU,Optional.empty(),List.of(),Optional.empty(),"");worker.invalidate();dataRevision=next;viewRevision=view;cpuRevision=0;sourceReady=false;dirty=false;latestVolume=null;latestStyle=null;latestLimits=null;status=waiting;
        try { releaseDisplay();return next; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Grid precision preflight and CPU admission precede token changes and disposal of prior output. */
    public long submit(NoiseSurfaceWorker.Request request) {
        Objects.requireNonNull(request);current();GlNoiseUpload.precision(request.volume().grid());long next=nextData(),view=nextView();var queued=new Status(next,view,request.geometry().isPresent()?Phase.RECOLORING:Phase.MESHING,Optional.of(request.volume().context()),List.of(),Optional.empty(),"");
        long cpu=worker.submit(request);dataRevision=next;viewRevision=view;cpuRevision=cpu;sourceReady=false;dirty=false;latestVolume=request.volume().id();latestStyle=request.style();latestLimits=request.limits();status=queued;
        try { releaseDisplay();return next; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Consumes detached native samples after destination admission; retain another input for retries/style changes. */
    public long submit(RenderInput<NoiseVolume> input,NoiseSurface.Style style,NoiseSurface.Limits limits) {
        Objects.requireNonNull(input);
        try(var source=input.retain()) {
            current();var memory=device.memory().orElse(null);if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Scalar view requires the same tracked input coordinator");
            return submit(new NoiseSurfaceWorker.Request(source.value(),style,limits));
        }finally { input.close(); }
    }
    public long recolor(MapRaster.ScalarStyle colors,NoiseSurface.Limits limits) {
        current();if(!sourceReady||scene==null)throw new GlFailure(GlFailure.Code.STATE,"Complete current scalar geometry is required for recoloring");return recolor(scene.surface(),colors,limits);
    }
    /** Reuse a caller-held displayed surface through rapid replacements; retain its owner until admission. */
    public long recolor(NoiseSurface source,MapRaster.ScalarStyle colors,NoiseSurface.Limits limits) {
        Objects.requireNonNull(source);current();
        if(latestStyle==null||!source.volume().id().equals(latestVolume)||!source.style().sameGeometry(latestStyle))throw new GlFailure(GlFailure.Code.STALE,"Recolor source differs from the latest scalar volume or geometry policy");
        return submit(NoiseSurfaceWorker.Request.recolor(source,colors,limits));
    }
    /** Consumes an independently retained CPU surface after destination admission or rejection. */
    public long recolor(RenderInput<NoiseSurface> input,MapRaster.ScalarStyle colors,NoiseSurface.Limits limits) {
        Objects.requireNonNull(input);
        try(var source=input.retain()) { tracked(source);return recolor(source.value(),colors,limits); }
        finally { input.close(); }
    }
    private void tracked(RenderInput<NoiseSurface> input) {
        current();var memory=device.memory().orElse(null);
        if(input.admission().tracked()&&(memory==null||!input.admission().belongsTo(memory)))throw new IllegalArgumentException("Scalar view requires the same tracked surface coordinator");
    }
    private void dimensions(int width,int height) { GlTarget.validate(device,width,height); }
    public long view(GlCamera camera,int width,int height) {
        Objects.requireNonNull(camera);current();dimensions(width,height);camera.matrix((double)width/height);if(camera.equals(this.camera)&&width==this.width&&height==this.height)return viewRevision;
        long next=nextView();var changed=displayStatus(next);this.camera=camera;this.width=width;this.height=height;viewRevision=next;dirty=true;status=changed;
        try { releaseFrame();return next; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    public WorldClip clip() { current();return clip; }
    /** Display-only scope; pending preparation/upload and complete source data stay unchanged. */
    public long clip(WorldClip clip) {
        Objects.requireNonNull(clip);current();if(clip.equals(this.clip))return viewRevision;long next=nextView();var changed=displayStatus(next);
        this.clip=clip;viewRevision=next;dirty=true;status=changed;
        try { releaseFrame();return viewRevision; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    public long fit(int width,int height) { return fit(width,height,WorldClip.NONE); }
    /** Display-only fit within the current clip; source samples and clipping stay unchanged. */
    public long fitClipped(int width,int height) { return fit(width,height,clip); }
    private long fit(int width,int height,WorldClip scope) {
        current();dimensions(width,height);if(!sourceReady||scene==null)throw new GlFailure(GlFailure.Code.STATE,"A complete current scalar upload is required before camera fitting");return view(GlOrbitCamera.fit(scene.surface(),(double)width/height,scope).camera(),width,height);
    }
    /** Display-only retry; source/CPU/upload failures require resubmission. */
    public long redraw() {
        current();if(!sourceReady||scene==null||camera==null)throw new GlFailure(GlFailure.Code.STATE,"Complete current scalar scene/camera required to redraw");long next=nextView();var waiting=displayStatus(next);viewRevision=next;dirty=true;status=waiting;
        try { releaseFrame();return next; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Retry a failed source upload from exact prepared data, preserving the source token and display settings. */
    public boolean retryUpload(long expectedDataRevision,NoiseSurface source) {
        Objects.requireNonNull(source);current();if(expectedDataRevision<1)throw new IllegalArgumentException("Invalid scalar data revision");
        if(expectedDataRevision!=dataRevision||sourceReady||status.phase()!=Phase.FAILED)return false;
        if(source.requiresPlaceholder()||latestStyle==null||!source.volume().id().equals(latestVolume)||!source.style().equals(latestStyle))throw new GlFailure(GlFailure.Code.STALE,"Retry requires the exact latest scalar volume and complete display style");
        if(source.size()>latestLimits.faces()||source.payloadBytes()>latestLimits.workingBytes())throw new GlFailure(GlFailure.Code.LIMIT,"Retry surface exceeds the latest scalar preparation allowance");
        GlNoiseUpload.validate(device,source);
        long next=nextView();var queued=new Status(dataRevision,next,Phase.UPLOADING,status.context(),List.of(),Optional.empty(),"");
        worker.invalidate();cpuRevision=0;viewRevision=next;dirty=false;
        try { releaseDisplay();upload=GlNoiseUpload.begin(device,source);status=queued;return true; }
        catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Consumes retained CPU ownership through retry admission, including stale-token and failure outcomes. */
    public boolean retryUpload(long expectedDataRevision,RenderInput<NoiseSurface> input) {
        Objects.requireNonNull(input);
        try(var source=input.retain()) { tracked(source);return retryUpload(expectedDataRevision,source.value()); }
        finally { input.close(); }
    }
    /** One bounded upload step; an admitted complete-frame draw remains synchronous. */
    public Status advance(GlUploadBudget budget,long expectedDataRevision) {
        Objects.requireNonNull(budget);current();if(expectedDataRevision<1)throw new IllegalArgumentException("Invalid scalar data revision");if(expectedDataRevision!=dataRevision||status.phase()==Phase.FAILED)return status;
        try {
            if(cpuRevision!=0) {
                var result=worker.take(cpuRevision);
                if(result.isPresent())try(var prepared=result.orElseThrow()) { upload=GlNoiseUpload.begin(device,prepared.input());cpuRevision=0;state(Phase.UPLOADING); }
                else {
                    var cpu=worker.status();if(cpu.revision()!=cpuRevision||cpu.phase()==NoiseSurfaceWorker.Phase.CLOSED)throw new GlFailure(GlFailure.Code.STALE,"Scalar worker changed before adoption");
                    if(cpu.phase()==NoiseSurfaceWorker.Phase.FAILED)status=new Status(dataRevision,viewRevision,Phase.FAILED,status.context(),cpu.problems(),Optional.empty(),"Scalar surface preparation failed");return status;
                }
            }
            if(upload!=null&&status.phase()==Phase.UPLOADING) { upload.step(budget,()->false);if(upload.ready()) { scene=upload.take();upload.close();upload=null;sourceReady=true;dirty=true;state(Phase.WAITING_VIEW); } }
            if(!sourceReady||scene==null||camera==null||!dirty)return status;
            if(completed!=null)throw new GlFailure(GlFailure.Code.STATE,"Untaken scalar frame survived view invalidation");
            var frame=GlNoiseFrame.render(renderer,viewRevision,scene,camera,width,height,clip,()->false);
            try { var ready=new Status(dataRevision,viewRevision,Phase.FRAME_READY,status.context(),List.of(),Optional.empty(),"");completed=frame;dirty=false;status=ready; }
            catch(RuntimeException|Error failure) { try { frame.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }return status;
        }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    public Optional<GlNoiseFrame> take(long expectedDataRevision,long expectedViewRevision) {
        current();if(!sourceReady||expectedDataRevision!=dataRevision||expectedViewRevision!=viewRevision||status.phase()!=Phase.FRAME_READY||completed==null||completed.info().revision()!=viewRevision)return Optional.empty();
        var value=Optional.of(completed);var delivered=new Status(dataRevision,viewRevision,Phase.DELIVERED,status.context(),List.of(),Optional.empty(),"");completed=null;status=delivered;return value;
    }
    private void releaseFrame() { if(completed!=null)try { completed.close();completed=null; }catch(RuntimeException|Error failure) { if(completed.closed())completed=null;throw failure; } }
    private void releaseDisplay() {
        Throwable failure=null;try { releaseFrame(); }catch(RuntimeException|Error error) { failure=error; }
        if(upload!=null)try { upload.close();upload=null; }catch(RuntimeException|Error error) { if(!upload.ownsResources())upload=null;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if(scene!=null)try { scene.close();scene=null; }catch(RuntimeException|Error error) { if(scene.closed())scene=null;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    @Override public void close() {
        if(disposed)return;device.current();closed=true;sourceReady=false;latestVolume=null;latestStyle=null;latestLimits=null;Throwable failure=null;
        try { worker.close(); }catch(RuntimeException|Error error) { failure=error; }
        try { releaseDisplay(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        try { status=new Status(dataRevision,viewRevision,Phase.CLOSED,Optional.empty(),List.of(),Optional.empty(),""); }
        catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }disposed=failure==null;
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
