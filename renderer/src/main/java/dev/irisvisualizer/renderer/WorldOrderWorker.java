package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
        @Override public void close() { owner.close();admission.close(); }
    }
    private static final class Job {
        final long revision,reservation;RenderMemory.Hold admission=RenderMemory.Hold.none();long retainedBytes;boolean releaseAdmission;final WorldScene.Result owner;final WorldDrawOrder.View view;final WorldDrawOrder.Limits limits;final UUID snapshotId;final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,long reservation,WorldScene.Result owner,WorldDrawOrder.View view,WorldDrawOrder.Limits limits,UUID snapshotId) { this.revision=revision;this.reservation=reservation;this.owner=owner;this.view=view;this.limits=limits;this.snapshotId=snapshotId; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final long workingBytes;private final RenderMemory memory;
    private long revision;private boolean closed,scheduled;private Job active,pending;private Completion completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public WorldOrderWorker() { this(1024L*1024*1024); }
    public WorldOrderWorker(long workingBytes) { this(workingBytes,null); }
    public WorldOrderWorker(long workingBytes,RenderMemory memory) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Draw-order worker limit exceeds 1 GiB");this.workingBytes=workingBytes;this.memory=memory;
        executor=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-world-order");thread.setDaemon(true);return thread;});
    }
    public long submit(WorldScene.Result owner,WorldDrawOrder.View view) { return submit(owner,view,WorldDrawOrder.Limits.DEFAULT); }
    /** Already-owned wrappers are rejected without closing their current owner; use retain() for new jobs. */
    public long submit(WorldScene.Result owner,WorldDrawOrder.View view,WorldDrawOrder.Limits limits) {
        Objects.requireNonNull(owner);boolean consumed=false;RenderMemory.Hold freshAdmission=RenderMemory.Hold.none();
        try {
            synchronized(lock) {
                if(active!=null&&active.owner==owner||pending!=null&&pending.owner==owner||completed!=null&&completed.owner()==owner) { consumed=true;throw new IllegalArgumentException("Draw-order worker already owns this scene wrapper"); }
                Objects.requireNonNull(view);Objects.requireNonNull(limits);long next=nextRevision();
                var scene=owner.scene().orElseThrow(()->new IllegalArgumentException("Translucent ordering requires a successful CPU scene"));
                if(scene.usage().knownPayloadBytes()>limits.workingBytes())throw new IllegalArgumentException("Sort source scene exceeds its declared working budget");
                long reservation=Math.min(limits.workingBytes(),scene.usage().knownPayloadBytes()+limits.sortBytes());
                if(reservation>workingBytes-(active==null?0:active.reservation))throw new IllegalArgumentException("Active and pending sort requests exceed the worker budget");
                var job=new Job(next,reservation,owner,view,limits,scene.snapshot().id());var queued=status(job,Phase.QUEUED,List.of());
                freshAdmission=memory==null?RenderMemory.Hold.none():memory.hold("Translucent ordering",reservation);job.admission=freshAdmission;
                cancelOwned();pending=job;consumed=true;revision=next;status=queued;
                if(!scheduled) {
                    scheduled=true;try { executor.execute(this::drain); }
                    catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;owner.close();job.admission.close();status=status(job,Phase.FAILED,List.of(problem(failure,"Draw-order worker could not be started"))); }
                }
                return next;
            }
        }finally { if(!consumed) { owner.close();freshAdmission.close(); } }
    }
    private long nextRevision() { if(closed)throw new IllegalStateException("Draw-order worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Draw-order revision counter exhausted");return revision+1; }
    private void cancelOwned() { if(active!=null)active.cancelled.set(true);if(pending!=null) { pending.cancelled.set(true);pending.owner.close();pending.admission.close();pending=null; }if(completed!=null) { completed.close();completed=null; } }
    public long invalidate() { synchronized(lock) { long next=nextRevision();cancelOwned();revision=next;status=new Status(next,Phase.IDLE,Optional.empty(),List.of());return next; } }
    public Status status() { synchronized(lock) { return status; } }
    public Optional<Completion> take(long expectedRevision) { synchronized(lock) { if(closed||revision!=expectedRevision||completed==null)return Optional.empty();var result=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.snapshotId(),List.of());completed=null;status=delivered;return result; } }
    private static Status status(Job job,Phase phase,List<WorldProblem> problems) { return new Status(job.revision,phase,Optional.of(job.snapshotId),problems); }
    private static WorldProblem problem(Throwable error,String reason) { return new WorldProblem(error instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,Optional.empty(),reason+": "+error.getClass().getSimpleName()); }
    private void drain() {
        while(true) {
            Job job;synchronized(lock) { if(closed||pending==null) { active=null;scheduled=false;return; }job=pending;pending=null;active=job; }
            try { execute(job); }finally { synchronized(lock) { active=null;if(job.releaseAdmission) { job.admission.shrinkTo(job.retainedBytes);job.admission.close(); }job=null; } }
        }
    }
    private void execute(Job job) {
        WorldDrawOrder result=null;List<WorldProblem> problems=List.of();boolean retained=false;
        try {
            synchronized(lock) { if(!closed&&revision==job.revision&&!job.cancelled.get())status=status(job,Phase.SORTING,List.of()); }
            try { result=WorldDrawOrder.prepare(job.owner.scene().orElseThrow(),job.view,job.limits,job::cancelled);if(result.requiresPlaceholder())problems=result.problems(); }
            catch(CancellationException superseded) { /* Superseded sorting never replaces newer status. */ }
            catch(RuntimeException|OutOfMemoryError failure) { problems=List.of(problem(failure,"Translucent face sorting failed")); }
            synchronized(lock) {
                if(!closed&&revision==job.revision&&!job.cancelled.get()) {
                    if(result!=null&&!result.requiresPlaceholder()) { var ready=status(job,Phase.READY,List.of());long bytes=job.owner.scene().orElseThrow().usage().knownPayloadBytes()+result.bytes();var hold=job.admission.retain();Completion value;
                        try { value=new Completion(job.revision,job.owner,result,hold); }catch(RuntimeException|OutOfMemoryError failure) { hold.close();throw failure; }
                        job.retainedBytes=bytes;completed=value;retained=true;status=ready; }
                    else { if(problems.isEmpty())problems=List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"Sorting ended without a complete face order"));status=status(job,Phase.FAILED,problems); }
                }
            }
        }finally { if(!retained)job.owner.close();job.releaseAdmission=true; }
    }
    @Override public void close() { synchronized(lock) { if(closed)return;var done=new Status(revision,Phase.CLOSED,Optional.empty(),List.of());closed=true;cancelOwned();status=done; }executor.shutdownNow(); }
}
