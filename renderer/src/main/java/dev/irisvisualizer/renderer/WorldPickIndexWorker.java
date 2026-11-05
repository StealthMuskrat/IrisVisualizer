package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Background section/range indexing. Every submission consumes one fresh CPU scene owner. */
public final class WorldPickIndexWorker implements AutoCloseable {
    public enum Phase { IDLE, WAITING, QUEUED, INDEXING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<UUID> snapshotId,List<WorldProblem> problems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(snapshotId);problems=List.copyOf(problems); }
    }
    /** Transfer/close the whole completion; its index independently owns source leases and admission. */
    public record Completion(long revision,UUID snapshotId,WorldPickIndex index) implements AutoCloseable {
        public Completion { Objects.requireNonNull(snapshotId);Objects.requireNonNull(index); }
        @Override public void close() { index.close(); }
    }
    private static final class Job {
        final long revision,reservation;final WorldScene.Result owner;final WorldPickIndex.Limits limits;final UUID snapshotId;
        final AtomicBoolean cancelled=new AtomicBoolean();RenderMemory.Hold admission=RenderMemory.Hold.none();long retainedBytes;boolean published;
        Job(long revision,long reservation,WorldScene.Result owner,WorldPickIndex.Limits limits,UUID snapshotId) {
            this.revision=revision;this.reservation=reservation;this.owner=owner;this.limits=limits;this.snapshotId=snapshotId;
        }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final RenderMemory memory;private final long workingBytes;
    private long revision,completedBytes;private boolean closed,scheduled,disposed;private Job active,pending;private Completion completed;private WorldScene.Result completedOwner;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public WorldPickIndexWorker() { this(1024L*1024*1024,null); }
    public WorldPickIndexWorker(RenderMemory memory) { this(1024L*1024*1024,memory); }
    public WorldPickIndexWorker(long workingBytes,RenderMemory memory) { this(workingBytes,memory,WorldPickIndexWorker::executor); }
    WorldPickIndexWorker(long workingBytes,RenderMemory memory,Supplier<ExecutorService> executors) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Picking preparation worker exceeds 1 GiB");
        this.workingBytes=workingBytes;this.memory=memory;
        executor=Objects.requireNonNull(Objects.requireNonNull(executors).get());
    }
    private static ExecutorService executor() { return Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-world-pick-index");thread.setDaemon(true);return thread;}); }
    public Optional<RenderMemory> memory() { return Optional.ofNullable(memory); }
    private long next() {
        if(closed)throw new IllegalStateException("Picking preparation worker is closed");
        if(revision==Long.MAX_VALUE)throw new IllegalStateException("Picking preparation revision exhausted");return revision+1;
    }
    private boolean current(Job job) { return !closed&&job.revision==revision&&!job.cancelled.get(); }
    private static Status state(Job job,Phase phase,List<WorldProblem> problems) { return new Status(job.revision,phase,Optional.of(job.snapshotId),problems); }
    private static Status failed(Job job,Throwable failure) {
        return state(job,Phase.FAILED,List.of(new WorldProblem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,Optional.empty(),"Picking index preparation failed: "+failure.getClass().getSimpleName())));
    }
    private void dropCompleted() {
        if(completed==null) { completedOwner=null;completedBytes=0;return; }
        try { completed.close();completed=null; }
        catch(RuntimeException|Error cleanup) { if(completed.index().released())completed=null;throw cleanup; }
        finally { if(completed==null) { completedOwner=null;completedBytes=0; } }
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
    public long submit(WorldScene.Result owner) { return submit(owner,WorldPickIndex.Limits.DEFAULT); }
    /** Rejecting a reused worker-owned wrapper never closes it. All other outcomes consume the fresh wrapper. */
    public long submit(WorldScene.Result owner,WorldPickIndex.Limits limits) {
        Objects.requireNonNull(owner);boolean consumed=false;Throwable rejection=null;RenderMemory.Hold fresh=RenderMemory.Hold.none();
        try {
            synchronized(lock) {
                if(active!=null&&active.owner==owner||pending!=null&&pending.owner==owner||completedOwner==owner) { consumed=true;throw new IllegalArgumentException("Picking worker already owns this scene wrapper; retain a fresh owner"); }
                Objects.requireNonNull(limits);long next=next();var scene=owner.scene().orElseThrow(()->new IllegalArgumentException("Picking preparation requires a complete CPU scene"));
                long reservation=WorldPickIndex.reservation(scene,limits);
                if(reservation>workingBytes-(active==null?0:active.reservation))throw new IllegalStateException("Active and pending picking preparation exceed their combined allowance");
                var job=new Job(next,reservation,owner,limits,scene.snapshot().id());var queued=state(job,Phase.QUEUED,List.of());
                fresh=memory==null?RenderMemory.Hold.none():memory.holdReplacing("World picking preparation",reservation,
                        pending==null?RenderMemory.Hold.none():pending.admission,completed==null?RenderMemory.Hold.none():completed.index().idleAdmission());
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
    public Optional<Completion> take(long expectedRevision) {
        synchronized(lock) {
            if(closed||expectedRevision!=revision||completed==null||completed.index().closed())return Optional.empty();
            var result=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.snapshotId(),List.of());
            completed=null;completedOwner=null;completedBytes=0;status=delivered;return result;
        }
    }
    private void execute(Job job) {
        WorldPickIndex index=null;Throwable escapingFailure=null;
        try {
            try {
                synchronized(lock) { if(current(job))status=state(job,Phase.INDEXING,List.of()); }
                WorldProblem.check(job::cancelled);
                index=WorldPickIndex.prepareAdmitted(job.owner,job.limits,memory,job.admission.retain(),job.reservation,job::cancelled);
                synchronized(lock) {
                    if(current(job)) {
                        if(index.requiresPlaceholder())status=state(job,Phase.FAILED,index.problems());
                        else {
                            long retained=index.queryPayloadBytes(null);if(retained>job.reservation)throw new IllegalStateException("Prepared picking output exceeded admitted peak");
                            var value=new Completion(job.revision,job.snapshotId,index);var ready=state(job,Phase.READY,List.of());
                            job.retainedBytes=retained;job.published=true;completedBytes=retained;completed=value;completedOwner=job.owner;status=ready;index=null;
                        }
                    }
                }
            }catch(RuntimeException|OutOfMemoryError failure) {
                synchronized(lock) { if(current(job)) { dropCompleted();status=failed(job,failure); } }
            }
        }catch(RuntimeException|Error failure) { escapingFailure=failure;throw failure; }
        finally {
            Throwable failure=escapingFailure;
            try { if(index!=null)index.close(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
            try { if(!job.published)job.owner.close(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
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
