package dev.irisvisualizer.renderer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Latest-selection, read-only asset capture. No native/GL access, download or readiness authority. */
public final class ClientAssetWorker implements AutoCloseable {
    public record Request(Path selected,String expectedVersion,ClientAssets.Limits limits) {
        public Request {
            Objects.requireNonNull(selected);Objects.requireNonNull(expectedVersion);Objects.requireNonNull(limits);
            if(!expectedVersion.equals(BlockModels.PROFILE))throw new IllegalArgumentException("Asset loading requires the exact pinned Minecraft profile");
            selected=selected.toAbsolutePath().normalize();
            if(selected.toString().length()>32768)throw new IllegalArgumentException("Client archive path exceeds its metadata bound");
        }
        public Request(Path selected) { this(selected,BlockModels.PROFILE,ClientAssets.Limits.DEFAULT); }
        /** Maximum of copy, directory-preflight and catalog/version-read phases; ZIP/parser objects are excluded. */
        public long reservation() { return ClientAssets.workingBytes(limits); }
    }
    public enum Phase { IDLE, WAITING, QUEUED, READING, READY, DELIVERED, FAILED, CLOSED }
    public enum Code { READ, MEMORY, WORKER }
    public record Problem(Code code,String reason) {
        public Problem { Objects.requireNonNull(code);Objects.requireNonNull(reason);if(reason.isBlank()||reason.length()>2048)throw new IllegalArgumentException("Unbounded asset-load problem"); }
    }
    /** Detached receipt only; polling does not retain the loaded catalog or reopen the archive. */
    public record Status(long revision,Phase phase,Optional<Path> selected,Optional<ClientAssetSnapshot.Receipt> assets,List<Problem> problems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(selected);Objects.requireNonNull(assets);problems=List.copyOf(problems); }
    }
    /** The input is an owned transfer handle. Retain it before a second consumer or retry. */
    public record Completion(long revision,RenderInput<ClientAssetSnapshot> input) implements AutoCloseable {
        public Completion { Objects.requireNonNull(input);if(revision<1)throw new IllegalArgumentException("Invalid completed asset revision"); }
        public ClientAssetSnapshot assets() { return input.value(); }
        public Completion retain() {
            var owned=input.retain();try { return new Completion(revision,owned); }catch(RuntimeException|Error failure) { try { owned.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
        }
        @Override public void close() { input.close(); }
    }
    private static final class Job {
        final long revision;final Request request;final AtomicBoolean cancelled=new AtomicBoolean();RenderMemory.Hold admission=RenderMemory.Hold.none();long retainedBytes;
        Job(long revision,Request request) { this.revision=revision;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
        void check() { if(cancelled())throw new CancellationException("Client asset selection is obsolete"); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final RenderMemory memory;private final long workingBytes;
    private long revision,completedBytes;private boolean closed,scheduled,disposed;private Job active,pending;private Completion completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),Optional.empty(),List.of());
    public ClientAssetWorker() { this(1024L*1024*1024,null); }
    public ClientAssetWorker(RenderMemory memory) { this(1024L*1024*1024,memory); }
    public ClientAssetWorker(long workingBytes,RenderMemory memory) { this(workingBytes,memory,ClientAssetWorker::executor); }
    ClientAssetWorker(long workingBytes,RenderMemory memory,Supplier<ExecutorService> executors) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Asset worker exceeds its working allowance");this.workingBytes=workingBytes;this.memory=memory;
        executor=Objects.requireNonNull(Objects.requireNonNull(executors).get());
    }
    private static ExecutorService executor() { return Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-client-assets");thread.setDaemon(true);return thread;}); }
    private long next() { if(closed)throw new IllegalStateException("Client asset worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Asset selection revision exhausted");return revision+1; }
    private boolean current(Job job) { return !closed&&job.revision==revision&&!job.cancelled.get(); }
    private static Status state(Job job,Phase phase,Optional<ClientAssetSnapshot.Receipt> assets,List<Problem> problems) { return new Status(job.revision,phase,Optional.of(job.request.selected()),assets,problems); }
    private static Status failed(Job job,Throwable failure) {
        String reason=failure instanceof IOException?failure.getMessage():"Client asset loading failed: "+failure.getClass().getSimpleName();
        if(reason==null||reason.isBlank())reason="Client archive could not be read";
        if(reason.length()>2048)reason=reason.substring(0,2048);
        return state(job,Phase.FAILED,Optional.empty(),List.of(new Problem(failure instanceof OutOfMemoryError?Code.MEMORY:failure instanceof IOException?Code.READ:Code.WORKER,reason)));
    }
    private void dropCompleted() { if(completed!=null) { completed.close();completed=null; }completedBytes=0; }
    private void cancelOwned() {
        if(active!=null)active.cancelled.set(true);
        if(pending!=null) { pending.cancelled.set(true);pending.admission.close();pending=null; }
        dropCompleted();
    }
    /** Metadata/admission only. Rejection preserves prior revision/work/completion and starts no file I/O. */
    public long submit(Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            long next=next(),activeBytes=active==null?0:active.request.reservation();
            if(request.reservation()>workingBytes-activeBytes)throw new IllegalStateException("Active and pending asset captures exceed their combined allowance");
            var job=new Job(next,request);var queued=state(job,Phase.QUEUED,Optional.empty(),List.of());
            job.admission=memory==null?RenderMemory.Hold.none():memory.holdReplacing("Client asset capture",request.reservation(),pending==null?RenderMemory.Hold.none():pending.admission,completed==null?RenderMemory.Hold.none():completed.input().admission());
            cancelOwned();pending=job;revision=next;status=queued;
            if(!scheduled) { scheduled=true;try { executor.execute(this::drain); }catch(RuntimeException|Error failure) { startFailed(job,failure); } }
            return next;
        }
    }
    public long invalidate() {
        synchronized(lock) { long next=next();var waiting=new Status(next,Phase.WAITING,Optional.empty(),Optional.empty(),List.of());cancelOwned();revision=next;status=waiting;return next; }
    }
    public Status status() { synchronized(lock) { return status; } }
    /** Active scratch stays included until unwinding. Transferred owners are outside this worker's local allowance. */
    public long reservedBytes() {
        synchronized(lock) { return (active==null?0:active.request.reservation())+(pending==null?0:pending.request.reservation())+(completed==null||active!=null&&active.revision==completed.revision()?0:completedBytes); }
    }
    public Optional<Completion> take(long expectedRevision) {
        synchronized(lock) {
            if(closed||expectedRevision!=revision||completed==null)return Optional.empty();
            var value=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.selected(),status.assets(),List.of());completed=null;completedBytes=0;status=delivered;return value;
        }
    }
    private void execute(Job job) {
        RenderInput<ClientAssetSnapshot> output=null;
        Throwable escapingFailure=null;
        try {
            try {
                job.check();synchronized(lock) { if(current(job))status=state(job,Phase.READING,Optional.empty(),List.of()); }
                var request=job.request;var assets=ClientAssets.load(request.selected(),request.expectedVersion(),request.limits(),job::cancelled);job.check();
                if(assets.receipt().capturedAssetBytes()>request.limits().capturedBytes())throw new IllegalStateException("Loaded catalog exceeds its admitted capture");
                output=RenderInput.own(assets,job.admission.retain());
                synchronized(lock) {
                    if(current(job)) {
                        var result=new Completion(job.revision,output);var ready=state(job,Phase.READY,Optional.of(assets.receipt()),List.of());
                        job.retainedBytes=assets.receipt().capturedAssetBytes();completed=result;completedBytes=job.retainedBytes;status=ready;output=null;
                    }
                }
            }catch(CancellationException cancelled) { /* Obsolete selection never becomes a ready catalog. */ }
            catch(IOException|RuntimeException|OutOfMemoryError failure) { synchronized(lock) { if(current(job)) { dropCompleted();status=failed(job,failure); } } }
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
    /** Cancels without waiting for file I/O or private capture cleanup; active memory remains charged until unwind. */
    @Override public void close() {
        Throwable failure=null;
        synchronized(lock) { if(disposed)return;closed=true;try { cancelOwned(); }catch(RuntimeException|Error error) { failure=error; } }
        try { executor.shutdownNow(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        synchronized(lock) {
            try { status=new Status(revision,Phase.CLOSED,Optional.empty(),Optional.empty(),List.of()); }
            catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }disposed=failure==null;
        }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
