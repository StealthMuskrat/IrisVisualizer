package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One active visual assembly plus one replaceable pending frozen request; no native/UI/GL calls. */
public final class WorldVisualWorker implements AutoCloseable {
    public enum Phase { IDLE, QUEUED, PREPARING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<WorldSnapshot.Source> source,Optional<UUID> snapshotId,List<WorldProblem> problems,List<ModelPlan.Problem> assetProblems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);problems=List.copyOf(problems);assetProblems=List.copyOf(assetProblems); }
    }
    public record Completion(long revision,WorldVisuals.Result result) { public Completion { Objects.requireNonNull(result); } }
    private static final class Job {
        final long revision;final WorldVisuals.Request request;final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,WorldVisuals.Request request) { this.revision=revision;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final long workingBytes;
    private boolean closed,scheduled;private long revision;private Job active,pending;private WorldVisuals.Result completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),Optional.empty(),List.of(),List.of());
    public WorldVisualWorker() { this(1024L*1024*1024); }
    /** Reserves each job's full declared CPU working ceiling, including a cancelled active request. */
    public WorldVisualWorker(long workingBytes) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Visual worker admission exceeds its 1 GiB ceiling");this.workingBytes=workingBytes;
        executor=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-world-visuals");thread.setDaemon(true);return thread;});
    }
    public long submit(WorldVisuals.Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            requireOpen();long reservation=request.limits().workingBytes()+(active==null?0:active.request.limits().workingBytes());
            if(reservation>workingBytes)throw new IllegalArgumentException("Active and pending visual working reservations exceed the worker budget");
            long next=nextRevision();var job=new Job(next,request);var queued=status(next,Phase.QUEUED,request,List.of(),List.of());
            if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);pending=job;completed=null;revision=next;status=queued;
            if(!scheduled) {
                scheduled=true;
                try { executor.execute(this::drain); }
                catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;status=status(next,Phase.FAILED,request,List.of(problem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,"The CPU visual worker could not be started")),List.of()); }
            }
            return next;
        }
    }
    /** Immediately prevents adoption even when no replacement world request is ready. */
    public long invalidate() {
        synchronized(lock) { requireOpen();long next=nextRevision();if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);pending=null;completed=null;revision=next;status=new Status(next,Phase.IDLE,Optional.empty(),Optional.empty(),List.of(),List.of());return next; }
    }
    public Status status() { synchronized(lock) { return status; } }
    /** Exactly-once transfer, only for the caller's still-current revision. Caller-owned results are uncharged. */
    public Optional<Completion> take(long expectedRevision) {
        synchronized(lock) {
            if(closed||revision!=expectedRevision||completed==null)return Optional.empty();var completion=new Completion(revision,completed);completed=null;status=new Status(revision,Phase.DELIVERED,status.source(),status.snapshotId(),List.of(),List.of());return Optional.of(completion);
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
                job=pending;pending=null;active=job;status=status(job.revision,Phase.PREPARING,job.request,List.of(),List.of());
            }
            // All temporary assembler/plan/texture graphs unwind before the active reservation is removed.
            execute(job);
            synchronized(lock) { active=null;job=null; }
        }
    }
    private void execute(Job job) {
        WorldVisuals.Result result=null;List<WorldProblem> failure=List.of();
        try { result=WorldVisuals.prepare(job.request,job::cancelled); }
        catch(CancellationException superseded) { /* Superseded results never change newer status. */ }
        catch(RuntimeException unexpected) { failure=List.of(problem(WorldProblem.Code.WORKER,"CPU visual assembly failed: "+unexpected.getClass().getSimpleName())); }
        catch(OutOfMemoryError exhausted) { failure=List.of(problem(WorldProblem.Code.MEMORY,"Not enough memory to complete CPU visual assembly")); }
        synchronized(lock) {
            if(closed||revision!=job.revision||job.cancelled.get())return;
            if(result!=null&&!result.requiresPlaceholder()) { completed=result;status=status(revision,Phase.READY,job.request,List.of(),List.of()); }
            else {
                var problems=result==null?failure:result.problems();var assets=result==null?List.<ModelPlan.Problem>of():result.assetProblems();
                if(problems.isEmpty()&&assets.isEmpty())problems=List.of(problem(WorldProblem.Code.WORKER,"CPU visual request ended without a complete result"));
                status=status(revision,Phase.FAILED,job.request,problems,assets);
            }
        }
    }
    /** Nonblocking cooperative cancellation and release of worker-owned CPU references. */
    @Override public void close() {
        synchronized(lock) { if(closed)return;closed=true;if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);pending=null;completed=null;status=new Status(revision,Phase.CLOSED,Optional.empty(),Optional.empty(),List.of(),List.of()); }
        executor.shutdownNow();
    }
}
