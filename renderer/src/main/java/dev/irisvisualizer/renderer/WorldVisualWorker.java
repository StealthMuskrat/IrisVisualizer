package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One active visual assembly plus one replaceable pending frozen request; no native/UI/GL calls. */
public final class WorldVisualWorker implements AutoCloseable {
    public enum Phase { IDLE, QUEUED, PREPARING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<WorldSnapshot.Source> source,Optional<UUID> snapshotId,List<WorldProblem> problems,List<ModelPlan.Problem> assetProblems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);problems=List.copyOf(problems);assetProblems=List.copyOf(assetProblems); }
    }
    public record Completion(long revision,WorldVisuals.Result result,RenderMemory.Hold admission) implements AutoCloseable {
        public Completion { Objects.requireNonNull(result);Objects.requireNonNull(admission); }
        public Completion(long revision,WorldVisuals.Result result) { this(revision,result,RenderMemory.Hold.none()); }
        @Override public void close() { admission.close(); }
    }
    private static final class Job {
        final long revision;final WorldVisuals.Request request;RenderMemory.Hold admission=RenderMemory.Hold.none();long retainedBytes;final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,WorldVisuals.Request request) { this.revision=revision;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final long workingBytes;private final RenderMemory memory;
    private boolean closed,scheduled,disposed;private long revision;private Job active,pending;private Completion completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),Optional.empty(),List.of(),List.of());
    public WorldVisualWorker() { this(1024L*1024*1024); }
    /** Reserves each job's full declared CPU working ceiling, including a cancelled active request. */
    public WorldVisualWorker(long workingBytes) { this(workingBytes,null); }
    public WorldVisualWorker(long workingBytes,RenderMemory memory) { this(workingBytes,memory,WorldVisualWorker::executor); }
    WorldVisualWorker(long workingBytes,RenderMemory memory,Supplier<ExecutorService> executors) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Visual worker admission exceeds its 1 GiB ceiling");this.workingBytes=workingBytes;this.memory=memory;
        executor=Objects.requireNonNull(Objects.requireNonNull(executors).get());
    }
    private static ExecutorService executor() { return Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-world-visuals");thread.setDaemon(true);return thread;}); }
    public long submit(WorldVisuals.Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            requireOpen();long reservation=request.limits().workingBytes()+(active==null?0:active.request.limits().workingBytes());
            if(reservation>workingBytes)throw new IllegalArgumentException("Active and pending visual working reservations exceed the worker budget");
            long next=nextRevision();var job=new Job(next,request);var queued=status(next,Phase.QUEUED,request,List.of(),List.of());
            job.admission=memory==null?RenderMemory.Hold.none():memory.holdReplacing("Standalone visual preparation",request.limits().workingBytes(),pending==null?RenderMemory.Hold.none():pending.admission,completed==null?RenderMemory.Hold.none():completed.admission());
            cancelOwned();pending=job;revision=next;status=queued;
            if(!scheduled) {
                scheduled=true;
                try { executor.execute(this::drain); }
                catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;job.admission.close();status=status(next,Phase.FAILED,request,List.of(problem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,"The CPU visual worker could not be started")),List.of()); }
            }
            return next;
        }
    }
    /** Immediately prevents adoption even when no replacement world request is ready. */
    public long invalidate() {
        synchronized(lock) { requireOpen();long next=nextRevision();cancelOwned();revision=next;status=new Status(next,Phase.IDLE,Optional.empty(),Optional.empty(),List.of(),List.of());return next; }
    }
    private void dropCompleted() { if(completed!=null) { completed.close();completed=null; } }
    private void cancelOwned() { if(active!=null)active.cancelled.set(true);if(pending!=null) { pending.cancelled.set(true);pending.admission.close();pending=null; }dropCompleted(); }
    public Status status() { synchronized(lock) { return status; } }
    /** Exactly-once transfer of output and its tracked memory hold for the current revision. */
    public Optional<Completion> take(long expectedRevision) {
        synchronized(lock) {
            if(closed||revision!=expectedRevision||completed==null)return Optional.empty();var completion=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.source(),status.snapshotId(),List.of(),List.of());completed=null;status=delivered;return completion;
        }
    }
    private void requireOpen() { if(closed)throw new IllegalStateException("Visual worker is closed"); }
    private long nextRevision() { if(revision==Long.MAX_VALUE)throw new IllegalStateException("Visual revision counter exhausted");return revision+1; }
    private static WorldProblem problem(WorldProblem.Code code,String reason) { return new WorldProblem(code,Optional.empty(),reason); }
    private static Status status(long revision,Phase phase,WorldVisuals.Request request,List<WorldProblem> problems,List<ModelPlan.Problem> assets) { return new Status(revision,phase,Optional.of(request.source()),Optional.of(request.snapshotId()),problems,assets); }
    private void drain() {
        while(true) {
            Job job;
            synchronized(lock) {
                if(closed||pending==null) { active=null;scheduled=false;return; }
                job=pending;pending=null;active=job;
            }
            // All temporary assembler/plan/texture graphs unwind before the active reservation is removed.
            try { execute(job); }
            finally { synchronized(lock) { active=null;job.admission.shrinkTo(job.retainedBytes);job.admission.close();job=null; } }
        }
    }
    private void execute(Job job) {
        try {
            synchronized(lock) { if(!closed&&revision==job.revision&&!job.cancelled.get())status=status(job.revision,Phase.PREPARING,job.request,List.of(),List.of()); }
            var result=WorldVisuals.prepare(job.request,job::cancelled);
            synchronized(lock) {
                if(closed||revision!=job.revision||job.cancelled.get())return;
                if(!result.requiresPlaceholder()) {
                    long bytes=result.usage().orElseThrow().knownWorkingBytes();
                    if(bytes>job.request.limits().workingBytes())throw new IllegalStateException("Visual output exceeds its admitted working ceiling");
                    var ready=status(revision,Phase.READY,job.request,List.of(),List.of());var hold=job.admission.retain();Completion value;
                    try { value=new Completion(revision,result,hold); }catch(RuntimeException|OutOfMemoryError failure) { hold.close();throw failure; }
                    job.retainedBytes=bytes;completed=value;status=ready;
                }else status=status(revision,Phase.FAILED,job.request,result.problems(),result.assetProblems());
            }
        }catch(CancellationException superseded) {
            synchronized(lock) { if(!closed&&revision==job.revision&&!job.cancelled.get())status=status(revision,Phase.FAILED,job.request,List.of(problem(WorldProblem.Code.WORKER,"CPU visual preparation cancelled before completion")),List.of()); }
        }catch(RuntimeException|OutOfMemoryError failure) {
            synchronized(lock) {
                if(!closed&&revision==job.revision&&!job.cancelled.get()) { dropCompleted();status=status(revision,Phase.FAILED,job.request,List.of(problem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,"CPU visual preparation failed: "+failure.getClass().getSimpleName())),List.of()); }
            }
        }
    }
    /** Nonblocking cooperative cancellation and release of worker-owned CPU references. */
    @Override public void close() {
        Throwable failure=null;
        synchronized(lock) { if(disposed)return;closed=true;try { cancelOwned(); }catch(RuntimeException|Error error) { failure=error; } }
        try { executor.shutdownNow(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        synchronized(lock) {
            try { status=new Status(revision,Phase.CLOSED,Optional.empty(),Optional.empty(),List.of(),List.of()); }
            catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }disposed=failure==null;
        }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
