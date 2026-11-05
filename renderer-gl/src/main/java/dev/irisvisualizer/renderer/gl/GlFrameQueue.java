package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.WorldSnapshot;
import dev.irisvisualizer.renderer.WorldClip;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Latest-revision completed-frame adoption. Displayed caller-owned frames are never modified. */
public final class GlFrameQueue implements AutoCloseable {
    public record Request(GlScene scene,Optional<GlAtlasFrame> atlas,Optional<GlDrawPlan> order,GlCamera camera,int width,int height,WorldClip clip) {
        public Request { Objects.requireNonNull(scene);Objects.requireNonNull(atlas);Objects.requireNonNull(order);Objects.requireNonNull(camera);Objects.requireNonNull(clip);if(width<1||height<1)throw new IllegalArgumentException("Invalid requested frame dimensions"); }
        public Request(GlScene scene,Optional<GlAtlasFrame> atlas,Optional<GlDrawPlan> order,GlCamera camera,int width,int height) { this(scene,atlas,order,camera,width,height,WorldClip.NONE); }
        public Request(GlScene scene,GlCamera camera,int width,int height) { this(scene,Optional.empty(),Optional.empty(),camera,width,height,WorldClip.NONE); }
    }
    public enum Phase { IDLE, WAITING, RENDERING, READY, DELIVERED, CANCELLED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<WorldSnapshot.Source> source,Optional<GlFrame.Info> frame,Optional<GlFailure.Code> code,String reason) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(source);Objects.requireNonNull(frame);Objects.requireNonNull(code);Objects.requireNonNull(reason); }
    }
    private final GlRenderer renderer;private final GlDevice device;private long revision;private boolean closed,rendering,disposed;private GlFrame completed;
    private volatile Status status=new Status(0,Phase.IDLE,Optional.empty(),Optional.empty(),Optional.empty(),"");
    public GlFrameQueue(GlRenderer renderer) { this.renderer=Objects.requireNonNull(renderer);device=renderer.device;device.live(renderer.allocation); }
    public Status status() { return status; }
    private void current() { device.current();if(closed||rendering)throw new GlFailure(GlFailure.Code.STATE,"Frame queue is closed or already rendering"); }
    private void releaseOwned() { if(completed==null)return;try { completed.close();completed=null; }catch(RuntimeException|Error failure) { if(completed.closed())completed=null;throw failure; } }
    private void failed(Throwable failure) {
        try {
            String reason=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());if(reason.length()>8192)reason=reason.substring(0,8192);var code=failure instanceof GlFailure gpu?gpu.code():failure instanceof OutOfMemoryError?GlFailure.Code.MEMORY:GlFailure.Code.STATE;status=new Status(revision,failure instanceof CancellationException?Phase.CANCELLED:Phase.FAILED,status.source(),Optional.empty(),Optional.of(code),reason);
        }catch(RuntimeException|Error publication) { if(publication!=failure)failure.addSuppressed(publication); }
    }
    public boolean invalidate(long next) {
        current();if(next<1)throw new IllegalArgumentException("Invalid frame revision");if(next<=revision)return false;var waiting=new Status(next,Phase.WAITING,Optional.empty(),Optional.empty(),Optional.empty(),"");revision=next;status=waiting;
        try { releaseOwned(); }catch(RuntimeException|Error failure) { failed(failure);throw failure; }return true;
    }
    /** The obsolete supplier must be side-effect free; it can observe an externally advanced edit/view token. */
    public boolean render(long next,Request request,BooleanSupplier obsolete) {
        Objects.requireNonNull(request);Objects.requireNonNull(obsolete);current();if(next<1)throw new IllegalArgumentException("Invalid frame revision");if(next<revision||next==revision&&status.phase()!=Phase.WAITING)return false;
        GlFrame.validate(renderer,request.scene(),request.atlas().orElse(null),request.order().orElse(null),request.camera(),request.width(),request.height());if(next>revision)invalidate(next);
        rendering=true;GlFrame result=null;
        try {
            status=new Status(revision,Phase.RENDERING,Optional.of(request.scene().cpuScene().snapshot().source()),Optional.empty(),Optional.empty(),"");
            result=GlFrame.render(renderer,next,request.scene(),request.atlas().orElse(null),request.order().orElse(null),request.camera(),request.width(),request.height(),request.clip(),obsolete);
            if(obsolete.getAsBoolean())throw new CancellationException("Completed frame became obsolete before queue adoption");var ready=new Status(revision,Phase.READY,status.source(),Optional.of(result.info()),Optional.empty(),"");completed=result;result=null;status=ready;return true;
        }catch(RuntimeException|Error failure) { if(result!=null)try { result.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }failed(failure);throw failure; }
        finally { rendering=false; }
    }
    public Optional<GlFrame> take(long latestRevision) {
        current();if(latestRevision<1)throw new IllegalArgumentException("Invalid frame revision");if(latestRevision>revision) { invalidate(latestRevision);return Optional.empty(); }
        if(latestRevision!=revision||status.phase()!=Phase.READY||completed==null||completed.info().revision()!=revision)return Optional.empty();var result=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.source(),status.frame(),Optional.empty(),"");completed=null;status=delivered;return result;
    }
    @Override public void close() {
        if(disposed)return;device.current();if(rendering)throw new GlFailure(GlFailure.Code.STATE,"Cannot close a frame queue from its obsolete supplier");closed=true;Throwable failure=null;
        try { releaseOwned(); }catch(RuntimeException|Error error) { failure=error; }
        try { status=new Status(revision,Phase.CLOSED,Optional.empty(),Optional.empty(),Optional.empty(),""); }
        catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        disposed=failure==null;
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
