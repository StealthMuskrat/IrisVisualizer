package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One active and one latest pending scalar display mesh, without native sampling on this executor. */
public final class NoiseSurfaceWorker implements AutoCloseable {
    public record Request(NoiseVolume volume,NoiseSurface.Style style,NoiseSurface.Limits limits,Optional<NoiseSurface> geometry) {
        public Request(NoiseVolume volume,NoiseSurface.Style style,NoiseSurface.Limits limits) { this(volume,style,limits,Optional.empty()); }
        public Request {
            Objects.requireNonNull(volume);Objects.requireNonNull(style);Objects.requireNonNull(limits);Objects.requireNonNull(geometry);
            if(volume.requiresPlaceholder()||volume.payloadBytes()>limits.workingBytes())throw new IllegalArgumentException("A complete scalar source within the working bound is required");
            if(geometry.isPresent()) {
                var source=geometry.orElseThrow();
                if(source.requiresPlaceholder()||source.volume()!=volume||!source.style().sameGeometry(style))throw new IllegalArgumentException("Scalar recoloring requires the exact complete volume and geometry policy");
                if(source.size()>limits.faces()||NoiseSurface.recolorReservation(source,style.colors())>limits.workingBytes())throw new IllegalArgumentException("Scalar recolor source and replacement colors exceed their declared allowance");
            }
        }
        public static Request recolor(NoiseSurface source,MapRaster.ScalarStyle colors,NoiseSurface.Limits limits) {
            Objects.requireNonNull(source);var old=source.style();return new Request(source.volume(),new NoiseSurface.Style(old.includeMinimum(),old.includeMaximum(),colors,old.meshing()),limits,Optional.of(source));
        }
        public long reservation() { return geometry.isPresent()?NoiseSurface.recolorReservation(geometry.orElseThrow(),style.colors()):NoiseSurface.reservation(volume,style,limits); }
    }
    public enum Phase { IDLE, WAITING, QUEUED, MESHING, RECOLORING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<UUID> volumeId,List<WorldProblem> problems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(volumeId);problems=List.copyOf(problems); }
    }
    public record Completion(long revision,RenderInput<NoiseSurface> input) implements AutoCloseable {
        public Completion { Objects.requireNonNull(input); }
        public NoiseSurface surface() { return input.value(); }
        @Override public void close() { input.close(); }
    }
    private static final class Job {
        final long revision;final Request request;final AtomicBoolean cancelled=new AtomicBoolean();RenderMemory.Hold admission=RenderMemory.Hold.none();long retainedBytes;
        Job(long revision,Request request) { this.revision=revision;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final RenderMemory memory;private final long workingBytes;
    private long revision,completedBytes;private boolean closed,scheduled,disposed;private Job active,pending;private Completion completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public NoiseSurfaceWorker() { this(1024L*1024*1024,null); }
    public NoiseSurfaceWorker(long workingBytes,RenderMemory memory) { this(workingBytes,memory,NoiseSurfaceWorker::executor); }
    NoiseSurfaceWorker(long workingBytes,RenderMemory memory,Supplier<ExecutorService> executors) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Scalar mesh worker exceeds its combined allowance");this.workingBytes=workingBytes;this.memory=memory;
        executor=Objects.requireNonNull(Objects.requireNonNull(executors).get());
    }
    private static ExecutorService executor() { return Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-noise-surfaces");thread.setDaemon(true);return thread;}); }
    private long next() { if(closed)throw new IllegalStateException("Scalar mesh worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Scalar mesh revision exhausted");return revision+1; }
    private boolean current(Job job) { return !closed&&job.revision==revision&&!job.cancelled.get(); }
    private static Status state(Job job,Phase phase,List<WorldProblem> problems) { return new Status(job.revision,phase,Optional.of(job.request.volume().id()),problems); }
    private static Status failed(Job job,Throwable failure) { return state(job,Phase.FAILED,List.of(new WorldProblem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,Optional.empty(),"Scalar surface preparation failed: "+failure.getClass().getSimpleName()))); }
    private void dropCompleted() { if(completed!=null) { completed.close();completed=null; }completedBytes=0; }
    private void cancelOwned() { if(active!=null)active.cancelled.set(true);if(pending!=null) { pending.cancelled.set(true);pending.admission.close();pending=null; }dropCompleted(); }
    /** Rejected admission preserves all prior work and revision. Active cancellation stays charged until unwind. */
    public long submit(Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            long next=next(),activeBytes=active==null?0:active.request.reservation();if(request.reservation()>workingBytes-activeBytes)throw new IllegalStateException("Active and pending scalar meshes exceed their combined working bound");
            var job=new Job(next,request);var queued=state(job,Phase.QUEUED,List.of());
            job.admission=memory==null?RenderMemory.Hold.none():memory.holdReplacing("Scalar surface preparation",request.reservation(),pending==null?RenderMemory.Hold.none():pending.admission,completed==null?RenderMemory.Hold.none():completed.input().admission());
            cancelOwned();pending=job;revision=next;status=queued;
            if(!scheduled) { scheduled=true;try { executor.execute(this::drain); }catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;job.admission.close();status=failed(job,failure); } }return next;
        }
    }
    /** Consumes a captured scalar owner after destination admission, including all rejection/failure outcomes. */
    public long submit(RenderInput<NoiseVolume> input,NoiseSurface.Style style,NoiseSurface.Limits limits) {
        Objects.requireNonNull(input);
        try(var source=input.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Scalar input requires the same managed coordinator");
            return submit(new Request(source.value(),style,limits));
        }finally { input.close(); }
    }
    /** Consumes complete surface ownership only after destination admission, including rejected submissions. */
    public long recolor(RenderInput<NoiseSurface> input,MapRaster.ScalarStyle colors,NoiseSurface.Limits limits) {
        Objects.requireNonNull(input);
        try(var source=input.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Scalar recoloring requires the same managed coordinator");
            return submit(Request.recolor(source.value(),colors,limits));
        }finally { input.close(); }
    }
    public long invalidate() { synchronized(lock) { long next=next();var waiting=new Status(next,Phase.WAITING,Optional.empty(),List.of());cancelOwned();revision=next;status=waiting;return next; } }
    public Status status() { synchronized(lock) { return status; } }
    public long reservedBytes() { synchronized(lock) { return (active==null?0:active.request.reservation())+(pending==null?0:pending.request.reservation())+(completed==null||active!=null&&active.revision==completed.revision()?0:completedBytes); } }
    public Optional<Completion> take(long expectedRevision) {
        synchronized(lock) { if(closed||expectedRevision!=revision||completed==null)return Optional.empty();var value=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.volumeId(),List.of());completed=null;completedBytes=0;status=delivered;return value; }
    }
    private void execute(Job job) {
        RenderInput<NoiseSurface> output=null;
        try {
            synchronized(lock) { if(current(job))status=state(job,job.request.geometry().isPresent()?Phase.RECOLORING:Phase.MESHING,List.of()); }
            var request=job.request;var result=request.geometry().isPresent()?NoiseSurface.recolor(request.geometry().orElseThrow(),request.style().colors(),request.limits(),job::cancelled):NoiseSurface.prepare(request.volume(),request.style(),request.limits(),job::cancelled);
            synchronized(lock) {
                if(current(job)) {
                    if(result.requiresPlaceholder())status=state(job,Phase.FAILED,result.problems());
                    else {
                        if(result.payloadBytes()>job.request.reservation())throw new IllegalStateException("Scalar output exceeds its admitted peak");
                        output=RenderInput.own(result,job.admission.retain());var complete=new Completion(job.revision,output);var ready=state(job,Phase.READY,List.of());
                        job.retainedBytes=result.payloadBytes();completed=complete;completedBytes=job.retainedBytes;status=ready;output=null;
                    }
                }
            }
        }catch(CancellationException cancellation) { /* Obsolete displays do not publish. */ }
        catch(RuntimeException|OutOfMemoryError failure) { synchronized(lock) { if(current(job)) { dropCompleted();status=failed(job,failure); } } }
        finally { if(output!=null)output.close(); }
    }
    private void drain() {
        while(true) {
            Job job;synchronized(lock) { if(closed||pending==null) { active=null;scheduled=false;return; }job=pending;pending=null;active=job; }
            try { execute(job); }finally { synchronized(lock) { active=null;var hold=job.admission;long retained=job.retainedBytes;job=null;hold.shrinkTo(retained);hold.close(); } }
        }
    }
    @Override public void close() {
        Throwable failure=null;
        synchronized(lock) { if(disposed)return;closed=true;try { cancelOwned(); }catch(RuntimeException|Error error) { failure=error; } }
        try { executor.shutdownNow(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        synchronized(lock) {
            try { status=new Status(revision,Phase.CLOSED,Optional.empty(),List.of()); }
            catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }disposed=failure==null;
        }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
