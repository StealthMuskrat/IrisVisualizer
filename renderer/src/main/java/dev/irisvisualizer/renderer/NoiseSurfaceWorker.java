package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One active and one latest pending scalar display mesh, without native sampling on this executor. */
public final class NoiseSurfaceWorker implements AutoCloseable {
    public record Request(NoiseVolume volume,NoiseSurface.Style style,NoiseSurface.Limits limits,Optional<NoiseSurface> geometry,Optional<ScalarHistogram.Limits> histogram) {
        public Request(NoiseVolume volume,NoiseSurface.Style style,NoiseSurface.Limits limits,Optional<NoiseSurface> geometry) { this(volume,style,limits,geometry,Optional.empty()); }
        public Request(NoiseVolume volume,NoiseSurface.Style style,NoiseSurface.Limits limits) { this(volume,style,limits,Optional.empty()); }
        public Request {
            Objects.requireNonNull(volume);Objects.requireNonNull(style);Objects.requireNonNull(limits);Objects.requireNonNull(geometry);Objects.requireNonNull(histogram);
            var bounded=bounded(volume,limits,histogram);
            if(volume.requiresPlaceholder()||volume.payloadBytes()>bounded.workingBytes())throw new IllegalArgumentException("A complete scalar source within the working bound is required");
            if(geometry.isPresent()) {
                var source=geometry.orElseThrow();
                if(source.requiresPlaceholder()||source.volume()!=volume||!source.style().sameGeometry(style))throw new IllegalArgumentException("Scalar recoloring requires the exact complete volume and geometry policy");
                if(source.size()>bounded.faces()||NoiseSurface.recolorReservation(source,style.colors())>bounded.workingBytes())throw new IllegalArgumentException("Scalar recolor source and replacement colors exceed their declared allowance");
            }
        }
        public static Request recolor(NoiseSurface source,MapRaster.ScalarStyle colors,NoiseSurface.Limits limits) {
            Objects.requireNonNull(source);var old=source.style();return new Request(source.volume(),new NoiseSurface.Style(old.includeMinimum(),old.includeMaximum(),colors,old.meshing()),limits,Optional.of(source));
        }
        private static NoiseSurface.Limits bounded(NoiseVolume volume,NoiseSurface.Limits limits,Optional<ScalarHistogram.Limits> histogram) {
            if(histogram.isEmpty())return limits;var requested=histogram.orElseThrow();long bytes=ScalarHistogram.requestedBytes(volume,requested),working=Math.min(limits.workingBytes(),requested.workingBytes());
            if(bytes>working)throw new IllegalArgumentException("Histogram metadata exceeds scalar working allowance");
            return new NoiseSurface.Limits(limits.faces(),working-bytes);
        }
        public Request withHistogram(ScalarHistogram.Limits histogram) { return new Request(volume,style,limits,geometry,Optional.of(histogram)); }
        NoiseSurface.Limits preparationLimits() { return bounded(volume,limits,histogram); }
        long histogramBytes() { return histogram.map(value->ScalarHistogram.requestedBytes(volume,value)).orElse(0L); }
        public long reservation() { return (geometry.isPresent()?NoiseSurface.recolorReservation(geometry.orElseThrow(),style.colors()):NoiseSurface.reservation(volume,style,preparationLimits()))+histogramBytes(); }
    }
    public enum Phase { IDLE, WAITING, QUEUED, MESHING, RECOLORING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<UUID> volumeId,List<WorldProblem> problems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(volumeId);problems=List.copyOf(problems); }
    }
    public record Completion(long revision,RenderInput<NoiseSurface> input,Optional<ScalarHistogram> histogram) implements AutoCloseable {
        public Completion { Objects.requireNonNull(input);Objects.requireNonNull(histogram);if(histogram.isPresent()&&!histogram.orElseThrow().matches(input.value()))throw new IllegalArgumentException("Scalar completion histogram belongs to another display"); }
        public Completion(long revision,RenderInput<NoiseSurface> input) { this(revision,input,Optional.empty()); }
        public NoiseSurface surface() { return input.value(); }
        public RenderInput<ScalarHistogram> retainHistogram() { return RenderInput.own(histogram.orElseThrow(()->new IllegalStateException("No histogram was requested")),input.admission().retain()); }
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
    private void dropCompleted() { var old=completed;completed=null;completedBytes=0;if(old!=null)old.close(); }
    private void cancelOwned() {
        if(active!=null)active.cancelled.set(true);Throwable failure=null;
        if(pending!=null) { var old=pending;pending=null;old.cancelled.set(true);try { old.admission.close(); }catch(RuntimeException|Error cleanup) { failure=cleanup; } }
        try { dropCompleted(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    /** Rejected admission preserves all prior work and revision. Active cancellation stays charged until unwind. */
    public long submit(Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            long next=next(),activeBytes=active==null?0:active.request.reservation();if(request.reservation()>workingBytes-activeBytes)throw new IllegalStateException("Active and pending scalar meshes exceed their combined working bound");
            var job=new Job(next,request);var queued=state(job,Phase.QUEUED,List.of());
            job.admission=memory==null?RenderMemory.Hold.none():memory.holdReplacing("Scalar surface preparation",request.reservation(),pending==null?RenderMemory.Hold.none():pending.admission,completed==null?RenderMemory.Hold.none():completed.input().admission());
            try { cancelOwned(); }catch(RuntimeException|Error failure) { try { job.admission.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }pending=job;revision=next;status=queued;
            if(!scheduled) { scheduled=true;try { executor.execute(this::drain); }catch(RuntimeException|Error failure) { startFailed(job,failure); } }return next;
        }
    }
    /** Consumes a captured scalar owner after destination admission, including all rejection/failure outcomes. */
    public long submit(RenderInput<NoiseVolume> input,NoiseSurface.Style style,NoiseSurface.Limits limits) {
        Objects.requireNonNull(input);
        try(input;var source=input.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Scalar input requires the same managed coordinator");
            return submit(new Request(source.value(),style,limits));
        }
    }
    public long submit(RenderInput<NoiseVolume> input,NoiseSurface.Style style,NoiseSurface.Limits limits,ScalarHistogram.Limits histogram) {
        Objects.requireNonNull(input);
        try(input;var source=input.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Scalar input requires the same managed coordinator");
            return submit(new Request(source.value(),style,limits).withHistogram(histogram));
        }
    }
    /** Consumes complete surface ownership only after destination admission, including rejected submissions. */
    public long recolor(RenderInput<NoiseSurface> input,MapRaster.ScalarStyle colors,NoiseSurface.Limits limits) {
        Objects.requireNonNull(input);
        try(input;var source=input.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Scalar recoloring requires the same managed coordinator");
            return submit(Request.recolor(source.value(),colors,limits));
        }
    }
    public long recolor(RenderInput<NoiseSurface> input,MapRaster.ScalarStyle colors,NoiseSurface.Limits limits,ScalarHistogram.Limits histogram) {
        Objects.requireNonNull(input);
        try(input;var source=input.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Scalar recoloring requires the same managed coordinator");
            return submit(Request.recolor(source.value(),colors,limits).withHistogram(histogram));
        }
    }
    public long invalidate() { synchronized(lock) { long next=next();var waiting=new Status(next,Phase.WAITING,Optional.empty(),List.of());cancelOwned();revision=next;status=waiting;return next; } }
    public Status status() { synchronized(lock) { return status; } }
    public long reservedBytes() { synchronized(lock) { return (active==null?0:active.request.reservation())+(pending==null?0:pending.request.reservation())+(completed==null||active!=null&&active.revision==completed.revision()?0:completedBytes); } }
    public Optional<Completion> take(long expectedRevision) {
        synchronized(lock) { if(closed||expectedRevision!=revision||completed==null)return Optional.empty();var value=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.volumeId(),List.of());completed=null;completedBytes=0;status=delivered;return value; }
    }
    private void execute(Job job) {
        RenderInput<NoiseSurface> output=null;
        Throwable escapingFailure=null;
        try {
            try {
                synchronized(lock) { if(current(job))status=state(job,job.request.geometry().isPresent()?Phase.RECOLORING:Phase.MESHING,List.of()); }
                var request=job.request;var result=request.geometry().isPresent()?NoiseSurface.recolor(request.geometry().orElseThrow(),request.style().colors(),request.preparationLimits(),job::cancelled):NoiseSurface.prepare(request.volume(),request.style(),request.preparationLimits(),job::cancelled);
                Optional<ScalarHistogram> histogram=Optional.empty();
                if(!result.requiresPlaceholder()&&request.histogram().isPresent()) {
                    long extra=request.reservation()-request.histogramBytes()-result.payloadBytes();
                    var captured=result.histogram(request.histogram().orElseThrow().reserve(Math.max(0,extra)),job::cancelled);
                    if(captured.requiresPlaceholder()) { synchronized(lock) { if(current(job))status=state(job,Phase.FAILED,captured.problems()); }return; }
                    histogram=Optional.of(captured);
                }
                long retained=result.payloadBytes()+histogram.map(ScalarHistogram::bytes).orElse(0L);
                synchronized(lock) {
                    if(current(job)) {
                        if(result.requiresPlaceholder())status=state(job,Phase.FAILED,result.problems());
                        else {
                            if(retained>job.request.reservation())throw new IllegalStateException("Scalar output exceeds its admitted peak");
                            output=RenderInput.own(result,job.admission.retain());var complete=new Completion(job.revision,output,histogram);var ready=state(job,Phase.READY,List.of());
                            job.retainedBytes=retained;completed=complete;completedBytes=job.retainedBytes;status=ready;output=null;
                        }
                    }
                }
            }catch(CancellationException cancellation) { /* Obsolete displays do not publish. */ }
            catch(RuntimeException|OutOfMemoryError failure) { synchronized(lock) { if(current(job)) { dropCompleted();status=failed(job,failure); } } }
        }catch(RuntimeException|Error failure) { escapingFailure=failure;throw failure; }
        finally {
            try { if(output!=null)output.close(); }
            catch(RuntimeException|Error cleanup) { if(escapingFailure==null)throw cleanup;if(cleanup!=escapingFailure)escapingFailure.addSuppressed(cleanup); }
        }
    }
    /** Caller holds the worker lock; a task that was never scheduled must not keep the dispatch gate set. */
    private void startFailed(Job job,Throwable failure) {
        scheduled=false;pending=null;boolean cleanupFailed=false;
        try { job.admission.close(); }catch(RuntimeException|Error cleanup) { cleanupFailed=true;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        try { status=failed(job,failure); }catch(RuntimeException|Error cleanup) { cleanupFailed=true;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        // Preserve existing FAILED-token returns for ordinary rejection/OOM; propagate other errors after cleanup.
        if(cleanupFailed||failure instanceof Error&&!(failure instanceof OutOfMemoryError)) {
            if(failure instanceof RuntimeException runtime)throw runtime;throw (Error)failure;
        }
    }
    /** An escaping job failure may publish only for that job, never over a newer pending request. */
    private void taskFailed(Job job,Throwable failure) {
        synchronized(lock) {
            if(!closed&&job.revision==revision&&!job.cancelled.get()&&completed==null)try { status=failed(job,failure); }
            catch(RuntimeException|Error publication) { if(publication!=failure)failure.addSuppressed(publication); }
        }
    }
    /** The previous drain has unwound; keep an already accepted latest request dispatchable. */
    private void drainFailed(Throwable failure) {
        synchronized(lock) {
            if(active!=null)taskFailed(active,failure);active=null;scheduled=false;
            if(!closed&&pending!=null) {
                var next=pending;scheduled=true;
                try { executor.execute(this::drain); }
                catch(RuntimeException|Error dispatch) {
                    try { startFailed(next,dispatch); }catch(RuntimeException|Error cleanup) { if(cleanup!=dispatch)dispatch.addSuppressed(cleanup); }
                    if(dispatch!=failure)failure.addSuppressed(dispatch);
                }
            }
        }
    }
    private void drain() {
        try {
            while(true) {
                Job job;synchronized(lock) { if(closed||pending==null) { active=null;scheduled=false;return; }job=pending;pending=null;active=job; }
                Throwable failure=null;
                try { execute(job); }
                catch(RuntimeException|Error error) { failure=error;taskFailed(job,error);throw error; }
                finally {
                    synchronized(lock) {
                        active=null;var hold=job.admission;long retained=job.retainedBytes;job=null;
                        try(hold) { hold.shrinkTo(retained); }
                        catch(RuntimeException|Error cleanup) { if(failure==null)throw cleanup;if(cleanup!=failure)failure.addSuppressed(cleanup); }
                    }
                }
            }
        }catch(RuntimeException|Error failure) { drainFailed(failure);throw failure; }
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
