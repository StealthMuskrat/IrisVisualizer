package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;

/** Latest CPU-map revision upload/adoption on the existing render thread. Displayed maps stay caller-owned. */
public final class GlMapQueue implements AutoCloseable {
    public enum Phase { IDLE, WAITING, UPLOADING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<MapSamples.Context> context,Optional<GlMapUpload.Progress> progress,Optional<GlFailure.Code> code,String reason) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(context);Objects.requireNonNull(progress);Objects.requireNonNull(code);Objects.requireNonNull(reason); }
    }
    public record Completion(long revision,GlMap map) implements AutoCloseable { public Completion { if(revision<1)throw new IllegalArgumentException("Invalid map revision");Objects.requireNonNull(map); }@Override public void close() { map.close(); } }
    private final GlDevice device;private long revision;private boolean closed;private GlMapUpload active;private GlMap completed;
    private volatile Status status=new Status(0,Phase.IDLE,Optional.empty(),Optional.empty(),Optional.empty(),"");
    public GlMapQueue(GlDevice device) { this.device=Objects.requireNonNull(device);device.current(); }
    public Status status() { return status; }
    private void current() { device.current();if(closed)throw new GlFailure(GlFailure.Code.STATE,"Map upload queue is closed"); }
    public boolean invalidate(long next) {
        current();if(next<1)throw new IllegalArgumentException("Invalid map revision");if(next<=revision)return false;
        var waiting=new Status(next,Phase.WAITING,Optional.empty(),Optional.empty(),Optional.empty(),"");revision=next;status=waiting;
        try { releaseOwned(); }catch(RuntimeException|Error failure) { failed(failure);throw failure; }return true;
    }
    /** Reuse retained native raster data after current failed-upload cleanup, without changing its token. */
    public boolean retry(long expectedRevision) {
        current();if(expectedRevision<1)throw new IllegalArgumentException("Invalid map revision");if(expectedRevision!=revision||status.phase()!=Phase.FAILED)return false;
        return restart(expectedRevision);
    }
    /** Connected-view recovery may abandon partial work after a failure outside this queue. */
    boolean restart(long expectedRevision) {
        current();if(expectedRevision<1)throw new IllegalArgumentException("Invalid map revision");if(expectedRevision!=revision)return false;
        var waiting=new Status(revision,Phase.WAITING,Optional.empty(),Optional.empty(),Optional.empty(),"");
        try { releaseOwned();status=waiting;return true; }catch(RuntimeException|Error failure) { failed(failure);throw failure; }
    }
    /** Immutable rasters are borrowed/retained, not consuming native generation leases. */
    public boolean submit(long next,MapRaster raster) {
        Objects.requireNonNull(raster);current();if(next<1)throw new IllegalArgumentException("Invalid map revision");if(next<revision||next==revision&&status.phase()!=Phase.WAITING)return false;if(next>revision)invalidate(next);
        status=new Status(revision,Phase.UPLOADING,Optional.of(raster.samples().context()),Optional.empty(),Optional.empty(),"");
        try { active=GlMapUpload.begin(device,raster);status=new Status(revision,Phase.UPLOADING,status.context(),Optional.of(active.progress()),Optional.empty(),"");return true; }
        catch(RuntimeException|Error failure) { try { releaseOwned(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }failed(failure);throw failure; }
    }
    public Status advance(GlUploadBudget budget,long latestRevision) {
        Objects.requireNonNull(budget);current();if(latestRevision<1)throw new IllegalArgumentException("Invalid map revision");if(latestRevision>revision) { invalidate(latestRevision);return status; }if(latestRevision<revision||status.phase()!=Phase.UPLOADING||active==null)return status;
        try { var progress=active.step(budget,()->false).progress();if(active.ready()) { completed=active.take();active.close();active=null;status=new Status(revision,Phase.READY,status.context(),Optional.of(progress),Optional.empty(),""); }else status=new Status(revision,Phase.UPLOADING,status.context(),Optional.of(progress),Optional.empty(),"");return status; }
        catch(RuntimeException|Error failure) { try { releaseOwned(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }failed(failure);throw failure; }
    }
    public Optional<Completion> take(long latestRevision) {
        current();if(latestRevision<1)throw new IllegalArgumentException("Invalid map revision");if(latestRevision>revision) { invalidate(latestRevision);return Optional.empty(); }if(latestRevision!=revision||status.phase()!=Phase.READY||completed==null)return Optional.empty();
        var value=Optional.of(new Completion(revision,completed));var delivered=new Status(revision,Phase.DELIVERED,status.context(),status.progress(),Optional.empty(),"");completed=null;status=delivered;return value;
    }
    private void failed(Throwable failure) { var code=failure instanceof GlFailure gpu?gpu.code():failure instanceof OutOfMemoryError?GlFailure.Code.MEMORY:GlFailure.Code.STATE;String reason=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());if(reason.length()>8192)reason=reason.substring(0,8192);status=new Status(revision,Phase.FAILED,status.context(),status.progress(),Optional.of(code),reason); }
    private void releaseOwned() {
        Throwable failure=null;
        if(active!=null)try { active.close();active=null; }catch(RuntimeException|Error error) { if(!active.ownsResources())active=null;failure=error; }
        if(completed!=null)try { completed.close();completed=null; }catch(RuntimeException|Error error) { if(completed.allocation.retired||completed.allocation.released)completed=null;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    @Override public void close() { if(closed&&active==null&&completed==null)return;closed=true;try { releaseOwned(); }finally { status=new Status(revision,Phase.CLOSED,Optional.empty(),Optional.empty(),Optional.empty(),""); } }
}
