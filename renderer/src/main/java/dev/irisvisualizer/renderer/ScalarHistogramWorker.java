package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Detached distributions and automatic ranges on one bounded CPU worker. Fresh input wrappers are consumed. */
public final class ScalarHistogramWorker implements AutoCloseable {
    public enum Phase { IDLE, WAITING, QUEUED, COUNTING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<ScalarHistogram.Receipt> receipt,List<WorldProblem> problems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(receipt);problems=List.copyOf(problems); }
    }
    /** Transfer/close the whole completion. Its owner retains detached histogram data only. */
    public record Completion(long revision,ScalarHistogram.Receipt receipt,Optional<ScalarHistogram.Range> requestedRange,RenderInput<ScalarHistogram> owner) implements AutoCloseable {
        public Completion {
            Objects.requireNonNull(receipt);Objects.requireNonNull(requestedRange);Objects.requireNonNull(owner);
            if(revision<1||owner.value().requiresPlaceholder()||!owner.value().receipt().equals(receipt)||requestedRange.isPresent()&&!requestedRange.equals(owner.value().range()))throw new IllegalArgumentException("Histogram completion receipt/range differs from its detached value");
        }
        public ScalarHistogram histogram() { return owner.value(); }
        public boolean closed() { return owner.closed(); }
        @Override public void close() { owner.close(); }
    }
    private record Source(ScalarHistogram.Receipt receipt,long reservation,Optional<ScalarHistogram.Range> range,Function<BooleanSupplier,ScalarHistogram> capture) {}
    private static final class Job {
        final long revision,reservation;final RenderInput<?> owner;final Source source;final ScalarHistogram.Receipt receipt;
        final AtomicBoolean cancelled=new AtomicBoolean();RenderMemory.Hold admission=RenderMemory.Hold.none();long retainedBytes;
        Job(long revision,RenderInput<?> owner,Source source) {
            this.revision=revision;this.reservation=source.reservation();this.owner=owner;this.source=source;this.receipt=source.receipt();
        }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final RenderMemory memory;private final long workingBytes;
    private long revision,completedBytes;private boolean closed,scheduled,disposed;private Job active,pending;private Completion completed;private RenderInput<?> completedOwner;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public ScalarHistogramWorker() { this(512L*1024*1024,null); }
    public ScalarHistogramWorker(RenderMemory memory) { this(512L*1024*1024,memory); }
    public ScalarHistogramWorker(long workingBytes,RenderMemory memory) { this(workingBytes,memory,ScalarHistogramWorker::executor); }
    ScalarHistogramWorker(long workingBytes,RenderMemory memory,Supplier<ExecutorService> executors) {
        if(workingBytes<0||workingBytes>512L*1024*1024)throw new IllegalArgumentException("Scalar histogram worker exceeds 512 MiB");
        this.workingBytes=workingBytes;this.memory=memory;
        executor=Objects.requireNonNull(Objects.requireNonNull(executors).get());
    }
    private static ExecutorService executor() { return Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-scalar-histogram");thread.setDaemon(true);return thread;}); }
    public Optional<RenderMemory> memory() { return Optional.ofNullable(memory); }
    /** Metadata-only host preflight before retaining a displayed source. Submission repeats the check. */
    public void validate() { synchronized(lock) { next(); } }
    private long next() {
        if(closed)throw new IllegalStateException("Scalar histogram worker is closed");
        if(revision==Long.MAX_VALUE)throw new IllegalStateException("Scalar histogram revision exhausted");return revision+1;
    }
    private boolean current(Job job) { return !closed&&job.revision==revision&&!job.cancelled.get(); }
    private static Status state(Job job,Phase phase,List<WorldProblem> problems) { return new Status(job.revision,phase,Optional.of(job.receipt),problems); }
    private static Status failed(Job job,Throwable failure) {
        return state(job,Phase.FAILED,List.of(new WorldProblem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,Optional.empty(),"Scalar histogram failed: "+failure.getClass().getSimpleName())));
    }
    private void dropCompleted() {
        var old=completed;completed=null;completedOwner=null;completedBytes=0;
        if(old!=null)old.close();
    }
    private void cancelOwned() {
        if(active!=null)active.cancelled.set(true);Throwable failure=null;
        if(pending!=null) {
            var old=pending;pending=null;old.cancelled.set(true);
            try { old.owner.close(); }catch(RuntimeException|Error cleanup) { failure=cleanup; }
            try { old.admission.close(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
        }
        try { dropCompleted(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    private static Source source(MapSamples samples,long borrowed,Optional<ScalarHistogram.Range> range,ScalarHistogram.Limits limits) {
        Objects.requireNonNull(range);Objects.requireNonNull(limits);ScalarHistogram.requestedBytes(samples,limits);
        return new Source(ScalarHistogram.receipt(samples),borrowed+ScalarHistogram.workingBytes(samples,limits),range,
                cancelled->ScalarHistogram.capture(samples,range,limits.reserve(borrowed),cancelled));
    }
    private static Source source(NoiseVolume volume,long borrowed,Optional<ScalarHistogram.Range> range,ScalarHistogram.Limits limits) {
        Objects.requireNonNull(range);Objects.requireNonNull(limits);ScalarHistogram.requestedBytes(volume,limits);
        return new Source(ScalarHistogram.receipt(volume),borrowed+ScalarHistogram.workingBytes(volume,limits),range,
                cancelled->ScalarHistogram.capture(volume,range,limits.reserve(borrowed),cancelled));
    }
    private static MapRaster.ScalarStyle colors(MapRaster raster) {
        if(raster.requiresPlaceholder()||raster.style() instanceof MapRaster.BiomeStyle)throw new IllegalArgumentException("Histogram requires a complete scalar raster");
        return raster.style() instanceof MapRaster.TerrainStyle terrain?terrain.colors():(MapRaster.ScalarStyle)raster.style();
    }
    private static NoiseSurface surface(RenderInput<NoiseSurface> owner) {
        var result=owner.value();if(result.requiresPlaceholder())throw new IllegalArgumentException("Histogram requires a complete scalar surface");return result;
    }
    private static Optional<ScalarHistogram.Range> range(MapRaster.ScalarStyle colors) { return Optional.of(new ScalarHistogram.Range(colors.minimum(),colors.maximum())); }
    /** Empty range selects complete-source observed extrema. No display data or native result is changed. */
    public long submitMap(RenderInput<MapSamples> owner,Optional<ScalarHistogram.Range> range,ScalarHistogram.Limits limits) {
        return submit(owner,limits,()->source(owner.value(),0,range,limits));
    }
    public long submitVolume(RenderInput<NoiseVolume> owner,Optional<ScalarHistogram.Range> range,ScalarHistogram.Limits limits) {
        return submit(owner,limits,()->source(owner.value(),0,range,limits));
    }
    /** Use the captured raster's exact display range. */
    public long submitRaster(RenderInput<MapRaster> owner,ScalarHistogram.Limits limits) {
        return submit(owner,limits,()-> { var raster=owner.value();var selected=range(colors(raster));return source(raster.samples(),raster.payloadBytes()-raster.samples().payloadBytes(),selected,limits); });
    }
    /** Empty range requests automatic extrema without recoloring the retained raster. */
    public long submitRaster(RenderInput<MapRaster> owner,Optional<ScalarHistogram.Range> range,ScalarHistogram.Limits limits) {
        return submit(owner,limits,()-> { var raster=owner.value();colors(raster);return source(raster.samples(),raster.payloadBytes()-raster.samples().payloadBytes(),range,limits); });
    }
    public long submitSurface(RenderInput<NoiseSurface> owner,ScalarHistogram.Limits limits) {
        return submit(owner,limits,()-> { var surface=surface(owner);return source(surface.volume(),surface.payloadBytes()-surface.volume().payloadBytes(),range(surface.style().colors()),limits); });
    }
    public long submitSurface(RenderInput<NoiseSurface> owner,Optional<ScalarHistogram.Range> range,ScalarHistogram.Limits limits) {
        return submit(owner,limits,()-> { var surface=surface(owner);return source(surface.volume(),surface.payloadBytes()-surface.volume().payloadBytes(),range,limits); });
    }
    /** Reject reused worker wrappers without closing them; consume fresh wrappers on all other outcomes. */
    private long submit(RenderInput<?> owner,ScalarHistogram.Limits limits,Supplier<Source> prepare) {
        Objects.requireNonNull(owner);boolean consumed=false;Throwable rejection=null;RenderMemory.Hold fresh=RenderMemory.Hold.none();
        try {
            synchronized(lock) {
                if(active!=null&&active.owner==owner||pending!=null&&pending.owner==owner||completedOwner==owner) { consumed=true;throw new IllegalArgumentException("Histogram worker already owns this wrapper; retain a fresh owner"); }
                Objects.requireNonNull(limits);long next=next();
                if(owner.admission().tracked()&&!owner.admission().belongsTo(memory))throw new IllegalArgumentException("Tracked histogram inputs require the same memory coordinator");
                var source=prepare.get();long reservation=source.reservation();
                if(reservation>limits.workingBytes())throw new IllegalArgumentException("Scalar histogram exceeds its declared working allowance");
                if(reservation>workingBytes-(active==null?0:active.reservation))throw new IllegalStateException("Active and pending histogram work exceed their combined allowance");
                var job=new Job(next,owner,source);var queued=state(job,Phase.QUEUED,List.of());
                fresh=memory==null?RenderMemory.Hold.none():memory.holdReplacing("Scalar histogram preparation",reservation,
                        pending==null?RenderMemory.Hold.none():pending.admission,completed==null?RenderMemory.Hold.none():completed.owner().admission());
                job.admission=fresh;cancelOwned();pending=job;consumed=true;revision=next;status=queued;
                if(!scheduled) {
                    scheduled=true;
                    try { executor.execute(this::drain); }
                    catch(RuntimeException|Error failure) { startFailed(job,failure); }
                }
                return next;
            }
        }catch(RuntimeException|Error failure) { rejection=failure;throw failure; }
        finally { if(!consumed) {
            Throwable failure=rejection;
            try { owner.close(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
            try { fresh.close(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(rejection==null) { if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error; }
        } }
    }
    public long invalidate() {
        synchronized(lock) { long next=next();var waiting=new Status(next,Phase.WAITING,Optional.empty(),List.of());cancelOwned();revision=next;status=waiting;return next; }
    }
    public Status status() { synchronized(lock) { return status; } }
    public long reservedBytes() {
        synchronized(lock) {
            return (active==null?0:active.reservation)+(pending==null?0:pending.reservation)+
                    (completed==null||active!=null&&active.revision==completed.revision()?0:completedBytes);
        }
    }
    public Optional<Completion> take(long expectedRevision) { return take(expectedRevision,null); }
    public Optional<Completion> take(long expectedRevision,ScalarHistogram.Receipt expectedReceipt) {
        synchronized(lock) {
            if(closed||expectedRevision!=revision||completed==null||completed.closed()||expectedReceipt!=null&&!expectedReceipt.equals(completed.receipt()))return Optional.empty();
            var result=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.receipt(),List.of());
            completed=null;completedOwner=null;completedBytes=0;status=delivered;return result;
        }
    }
    private void execute(Job job) {
        RenderInput<ScalarHistogram> output=null;Throwable escapingFailure=null;
        try {
            try {
                synchronized(lock) { if(current(job))status=state(job,Phase.COUNTING,List.of()); }
                WorldProblem.check(job::cancelled);
                var histogram=job.source.capture().apply(job::cancelled);
                if(histogram.requiresPlaceholder()) {
                    var failed=state(job,Phase.FAILED,histogram.problems());job.owner.close();
                    synchronized(lock) { if(current(job))status=failed; }
                }else {
                    if(histogram.bytes()>job.reservation)throw new IllegalStateException("Detached histogram exceeded its admitted peak");
                    output=RenderInput.own(histogram,job.admission.retain());
                    var value=new Completion(job.revision,histogram.receipt(),job.source.range(),output);var ready=state(job,Phase.READY,List.of());
                    // READY output must never extend source ownership or hide a failed source release.
                    job.owner.close();WorldProblem.check(job::cancelled);
                    synchronized(lock) {
                        if(current(job)) {
                            job.retainedBytes=histogram.bytes();completedBytes=histogram.bytes();completed=value;completedOwner=job.owner;status=ready;output=null;
                        }
                    }
                }
            }catch(RuntimeException|OutOfMemoryError failure) {
                synchronized(lock) { if(current(job)) { dropCompleted();status=failed(job,failure); } }
            }
        }catch(RuntimeException|Error failure) { escapingFailure=failure;throw failure; }
        finally {
            Throwable failure=escapingFailure;
            try { if(output!=null)output.close(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
            try { job.owner.close(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(escapingFailure==null) { if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error; }
        }
    }
    /** Caller holds the worker lock; a task that was never scheduled must not keep the dispatch gate set. */
    private void startFailed(Job job,Throwable failure) {
        scheduled=false;pending=null;boolean cleanupFailed=false;
        try { job.owner.close(); }catch(RuntimeException|Error cleanup) { cleanupFailed=true;if(cleanup!=failure)failure.addSuppressed(cleanup); }
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
