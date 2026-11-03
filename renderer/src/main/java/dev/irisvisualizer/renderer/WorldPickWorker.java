package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One active query and one latest pending pointer request; results contain no retained scene graph. */
public final class WorldPickWorker implements AutoCloseable {
    public record Request(WorldPickIndex index,WorldRay ray,WorldPickIndex.Options options,Optional<AtlasRaster> displayed) {
        public Request { Objects.requireNonNull(index);Objects.requireNonNull(ray);Objects.requireNonNull(options);Objects.requireNonNull(displayed); }
        public Request(WorldPickIndex index,WorldRay ray) { this(index,ray,WorldPickIndex.Options.DEFAULT,Optional.empty()); }
    }
    public enum Phase { IDLE, WAITING, QUEUED, PICKING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<UUID> indexId,List<WorldProblem> problems) { public Status { Objects.requireNonNull(phase);Objects.requireNonNull(indexId);problems=List.copyOf(problems); } }
    public record Completion(long revision,WorldPick result) { public Completion { Objects.requireNonNull(result); } }
    private static final class Job {
        final long revision;final Request request;RenderMemory.Hold admission=RenderMemory.Hold.none();final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,Request request) { this.revision=revision;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final RenderMemory memory;private long revision;private boolean closed,scheduled;private Job active,pending;private WorldPick completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public WorldPickWorker() { this(null); }
    public WorldPickWorker(RenderMemory memory) { this.memory=memory;executor=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-world-pick");thread.setDaemon(true);return thread;}); }
    private long next() { if(closed)throw new IllegalStateException("Picking worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Picking revision counter exhausted");return revision+1; }
    private void cancelOwned() { if(active!=null)active.cancelled.set(true);if(pending!=null) { pending.cancelled.set(true);pending.admission.close();pending=null; }completed=null; }
    public long invalidate() { synchronized(lock) { long next=next();var waiting=new Status(next,Phase.WAITING,Optional.empty(),List.of());cancelOwned();revision=next;status=waiting;return next; } }
    public long submit(Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            long next=next();var job=new Job(next,request);var queued=new Status(next,Phase.QUEUED,Optional.of(request.index().id()),List.of());
            if(memory!=null)job.admission=memory.holdReplacing("Pointer picking request",request.index().queryPayloadBytes(request.displayed().orElse(null)),pending==null?RenderMemory.Hold.none():pending.admission);
            cancelOwned();pending=job;revision=next;status=queued;
            if(!scheduled) { scheduled=true;try { executor.execute(this::drain); }catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;job.admission.close();status=failed(job,failure); } }return next;
        }
    }
    public Status status() { synchronized(lock) { return status; } }
    public Optional<Completion> take(long latestRevision) {
        synchronized(lock) { if(closed||latestRevision!=revision||completed==null)return Optional.empty();var value=Optional.of(new Completion(revision,completed));var delivered=new Status(revision,Phase.DELIVERED,status.indexId(),List.of());completed=null;status=delivered;return value; }
    }
    private static Status failed(Job job,Throwable failure) { return new Status(job.revision,Phase.FAILED,Optional.of(job.request.index().id()),List.of(new WorldProblem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,Optional.empty(),"Picking request failed: "+failure.getClass().getSimpleName()))); }
    private void execute(Job job) {
        try {
            synchronized(lock) { if(!closed&&job.revision==revision&&!job.cancelled.get())status=new Status(job.revision,Phase.PICKING,Optional.of(job.request.index().id()),List.of()); }
            var request=job.request;var result=request.index().query(request.ray(),request.options(),request.displayed().orElse(null),job::cancelled);
            synchronized(lock) {
                if(!closed&&job.revision==revision&&!job.cancelled.get()) {
                    if(request.index().closed())status=failed(job,new CancellationException("Picking index closed before adoption"));
                    else if(result.requiresPlaceholder())status=new Status(job.revision,Phase.FAILED,Optional.of(result.indexId()),result.problems());
                    else { var ready=new Status(job.revision,Phase.READY,Optional.of(result.indexId()),List.of());completed=result;status=ready; }
                }
            }
        }catch(CancellationException cancelled) { synchronized(lock) { if(!closed&&job.revision==revision&&!job.cancelled.get())status=failed(job,cancelled); } }
        catch(RuntimeException|OutOfMemoryError failure) { synchronized(lock) { if(!closed&&job.revision==revision&&!job.cancelled.get()) { completed=null;status=failed(job,failure); } } }
    }
    private void drain() {
        while(true) { Job job;synchronized(lock) { if(closed||pending==null) { active=null;scheduled=false;return; }job=pending;pending=null;active=job; }try { execute(job); }finally { synchronized(lock) { active=null;job.admission.close();job=null; } } }
    }
    /** Caller-owned indices and atlas frames are never closed by the worker. */
    @Override public void close() { synchronized(lock) { if(closed)return;var done=new Status(revision,Phase.CLOSED,Optional.empty(),List.of());closed=true;cancelOwned();status=done; }executor.shutdownNow(); }
}
