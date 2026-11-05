package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Latest camera-bound translucent sorting. Every submitted fresh CPU owner is consumed. */
public final class WorldOrderWorker implements AutoCloseable {
    public enum Phase { IDLE, QUEUED, SORTING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<UUID> snapshotId,List<WorldProblem> problems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(snapshotId);problems=List.copyOf(problems); }
    }
    /** Keeps the source cache tickets alive until transferred order preparation is no longer in use. */
    public record Completion(long revision,WorldScene.Result owner,WorldDrawOrder order,RenderMemory.Hold admission) implements AutoCloseable {
        public Completion { Objects.requireNonNull(owner);Objects.requireNonNull(order);Objects.requireNonNull(admission); }
        public Completion(long revision,WorldScene.Result owner,WorldDrawOrder order) { this(revision,owner,order,RenderMemory.Hold.none()); }
        @Override public void close() {
            Throwable failure=null;try { owner.close(); }catch(RuntimeException|Error cleanup) { failure=cleanup; }
            try { admission.close(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
        }
    }
    private static final class Job {
        final long revision,reservation;RenderMemory.Hold admission=RenderMemory.Hold.none();long retainedBytes;boolean releaseAdmission;final WorldScene.Result owner;final WorldDrawOrder.View view;final WorldDrawOrder.Limits limits;final UUID snapshotId;final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,long reservation,WorldScene.Result owner,WorldDrawOrder.View view,WorldDrawOrder.Limits limits,UUID snapshotId) { this.revision=revision;this.reservation=reservation;this.owner=owner;this.view=view;this.limits=limits;this.snapshotId=snapshotId; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final long workingBytes;private final RenderMemory memory;
    private long revision,completedReservation;private boolean closed,scheduled,disposed;private Job active,pending;private Completion completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public WorldOrderWorker() { this(1024L*1024*1024); }
    public WorldOrderWorker(long workingBytes) { this(workingBytes,null); }
    public WorldOrderWorker(long workingBytes,RenderMemory memory) { this(workingBytes,memory,WorldOrderWorker::executor); }
    WorldOrderWorker(long workingBytes,RenderMemory memory,Supplier<ExecutorService> executors) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Draw-order worker limit exceeds 1 GiB");this.workingBytes=workingBytes;this.memory=memory;
        executor=Objects.requireNonNull(Objects.requireNonNull(executors).get());
    }
    private static ExecutorService executor() { return Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-world-order");thread.setDaemon(true);return thread;}); }
    public long submit(WorldScene.Result owner,WorldDrawOrder.View view) { return submit(owner,view,WorldDrawOrder.Limits.DEFAULT); }
    /** Already-owned wrappers are rejected without closing their current owner; use retain() for new jobs. */
    public long submit(WorldScene.Result owner,WorldDrawOrder.View view,WorldDrawOrder.Limits limits) {
        Objects.requireNonNull(owner);boolean consumed=false;Throwable rejection=null;RenderMemory.Hold freshAdmission=RenderMemory.Hold.none();
        try {
            synchronized(lock) {
                if(active!=null&&active.owner==owner||pending!=null&&pending.owner==owner||completed!=null&&completed.owner()==owner) { consumed=true;throw new IllegalArgumentException("Draw-order worker already owns this scene wrapper"); }
                Objects.requireNonNull(view);Objects.requireNonNull(limits);long next=nextRevision();
                var scene=owner.scene().orElseThrow(()->new IllegalArgumentException("Translucent ordering requires a successful CPU scene"));
                if(WorldDrawOrder.directionLength(view)<1e-8)throw new IllegalArgumentException("View direction is too small for stable sorting");
                long reservation=WorldDrawOrder.reservation(scene,limits);
                if(reservation>workingBytes-(active==null?0:active.reservation))throw new IllegalArgumentException("Active and pending sort requests exceed the worker budget");
                var job=new Job(next,reservation,owner,view,limits,scene.snapshot().id());var queued=status(job,Phase.QUEUED,List.of());
                freshAdmission=memory==null?RenderMemory.Hold.none():memory.hold("Translucent ordering",reservation);job.admission=freshAdmission;
                cancelOwned();pending=job;consumed=true;revision=next;status=queued;
                if(!scheduled) {
                    scheduled=true;try { executor.execute(this::drain); }
                    catch(RuntimeException|Error failure) { startFailed(job,failure); }
                }
                return next;
            }
        }catch(RuntimeException|Error failure) { rejection=failure;throw failure; }
        finally { if(!consumed) {
            Throwable failure=rejection;
            try { owner.close(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
            try { freshAdmission.close(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(rejection==null) { if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error; }
        } }
    }
    private long nextRevision() { if(closed)throw new IllegalStateException("Draw-order worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Draw-order revision counter exhausted");return revision+1; }
    private void cancelOwned() {
        if(active!=null)active.cancelled.set(true);Throwable failure=null;
        if(pending!=null) {
            var old=pending;pending=null;old.cancelled.set(true);
            try { old.owner.close(); }catch(RuntimeException|Error cleanup) { failure=cleanup; }
            try { old.admission.close(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
        }
        var old=completed;completed=null;completedReservation=0;
        try { if(old!=null)old.close(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    public long invalidate() { synchronized(lock) { long next=nextRevision();var waiting=new Status(next,Phase.IDLE,Optional.empty(),List.of());cancelOwned();revision=next;status=waiting;return next; } }
    public Status status() { synchronized(lock) { return status; } }
    /** Active scratch remains charged through unwind; untaken completed output is counted once. */
    public long reservedBytes() { synchronized(lock) { return (active==null?0:active.reservation)+(pending==null?0:pending.reservation)+(completed==null||active!=null&&active.revision==completed.revision()?0:completedReservation); } }
    public Optional<Completion> take(long expectedRevision) { synchronized(lock) { if(closed||revision!=expectedRevision||completed==null)return Optional.empty();var result=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.snapshotId(),List.of());completed=null;completedReservation=0;status=delivered;return result; } }
    private static Status status(Job job,Phase phase,List<WorldProblem> problems) { return new Status(job.revision,phase,Optional.of(job.snapshotId),problems); }
    private static WorldProblem problem(Throwable error,String reason) { return new WorldProblem(error instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,Optional.empty(),reason+": "+error.getClass().getSimpleName()); }
    /** Caller holds the worker lock; a task that was never scheduled must not keep the dispatch gate set. */
    private void startFailed(Job job,Throwable failure) {
        scheduled=false;pending=null;boolean cleanupFailed=false;
        try { job.owner.close(); }catch(RuntimeException|Error cleanup) { cleanupFailed=true;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        try { job.admission.close(); }catch(RuntimeException|Error cleanup) { cleanupFailed=true;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        try { status=status(job,Phase.FAILED,List.of(problem(failure,"Draw-order worker could not be started"))); }catch(RuntimeException|Error cleanup) { cleanupFailed=true;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        // Preserve existing FAILED-token returns for ordinary rejection/OOM; propagate other errors after cleanup.
        if(cleanupFailed||failure instanceof Error&&!(failure instanceof OutOfMemoryError)) {
            if(failure instanceof RuntimeException runtime)throw runtime;throw (Error)failure;
        }
    }
    /** An escaping job failure may publish only for that job, never over a newer pending request. */
    private void taskFailed(Job job,Throwable failure) {
        synchronized(lock) {
            if(!closed&&job.revision==revision&&!job.cancelled.get()&&completed==null)try { status=status(job,Phase.FAILED,List.of(problem(failure,"Translucent order drain failed"))); }
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
                        active=null;var hold=job.admission;long retained=job.retainedBytes;boolean release=job.releaseAdmission;job=null;
                        try { if(release)try(hold) { hold.shrinkTo(retained); } }
                        catch(RuntimeException|Error cleanup) { if(failure==null)throw cleanup;if(cleanup!=failure)failure.addSuppressed(cleanup); }
                    }
                }
            }
        }catch(RuntimeException|Error failure) { drainFailed(failure);throw failure; }
    }
    private void execute(Job job) {
        WorldDrawOrder result=null;List<WorldProblem> problems=List.of();boolean retained=false;Throwable escapingFailure=null;
        try {
            try {
                synchronized(lock) { if(!closed&&revision==job.revision&&!job.cancelled.get())status=status(job,Phase.SORTING,List.of()); }
                try { result=WorldDrawOrder.prepare(job.owner.scene().orElseThrow(),job.view,job.limits,job::cancelled);if(result.requiresPlaceholder())problems=result.problems(); }
                catch(CancellationException superseded) { /* Superseded sorting never replaces newer status. */ }
                catch(RuntimeException|OutOfMemoryError failure) { problems=List.of(problem(failure,"Translucent face sorting failed")); }
                synchronized(lock) {
                    if(!closed&&revision==job.revision&&!job.cancelled.get()) {
                        if(result!=null&&!result.requiresPlaceholder()) { var ready=status(job,Phase.READY,List.of());long bytes=job.owner.scene().orElseThrow().usage().knownPayloadBytes()+result.bytes();var hold=job.admission.retain();Completion value;
                            try { value=new Completion(job.revision,job.owner,result,hold); }catch(RuntimeException|OutOfMemoryError failure) { hold.close();throw failure; }
                            job.retainedBytes=bytes;completed=value;completedReservation=bytes;retained=true;status=ready; }
                        else { if(problems.isEmpty())problems=List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Sorting ended without a complete face order"));status=status(job,Phase.FAILED,problems); }
                    }
                }
            }catch(RuntimeException|OutOfMemoryError failure) {
                synchronized(lock) { if(!closed&&revision==job.revision&&!job.cancelled.get())status=status(job,Phase.FAILED,List.of(problem(failure,"Translucent order publication failed"))); }
            }
        }catch(RuntimeException|Error failure) { escapingFailure=failure;throw failure; }
        finally {
            try { if(!retained)job.owner.close(); }
            catch(RuntimeException|Error cleanup) { if(escapingFailure==null)throw cleanup;if(cleanup!=escapingFailure)escapingFailure.addSuppressed(cleanup); }
            finally { job.releaseAdmission=true; }
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
