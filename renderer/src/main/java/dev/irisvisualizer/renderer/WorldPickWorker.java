package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One active query and one latest pending pointer request; results contain no retained scene graph. */
public final class WorldPickWorker implements AutoCloseable {
    public record Frame(UUID id,long revision) {
        public Frame { Objects.requireNonNull(id);if(revision<1)throw new IllegalArgumentException("Invalid world picking frame revision"); }
    }
    public record Request(WorldPickIndex index,WorldRay ray,WorldPickIndex.Options options,Optional<AtlasRaster> displayed,Optional<Frame> frame) {
        public Request { Objects.requireNonNull(index);Objects.requireNonNull(ray);Objects.requireNonNull(options);Objects.requireNonNull(displayed);Objects.requireNonNull(frame); }
        public Request(WorldPickIndex index,WorldRay ray,WorldPickIndex.Options options,Optional<AtlasRaster> displayed) { this(index,ray,options,displayed,Optional.empty()); }
        public Request(WorldPickIndex index,WorldRay ray) { this(index,ray,WorldPickIndex.Options.DEFAULT,Optional.empty()); }
    }
    public enum Phase { IDLE, WAITING, QUEUED, PICKING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<UUID> indexId,List<WorldProblem> problems,Optional<Frame> frame) {
        public Status(long revision,Phase phase,Optional<UUID> indexId,List<WorldProblem> problems) { this(revision,phase,indexId,problems,Optional.empty()); }
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(indexId);Objects.requireNonNull(frame);problems=List.copyOf(problems); }
    }
    public record Completion(long revision,WorldPick result,Optional<Frame> frame) {
        public Completion(long revision,WorldPick result) { this(revision,result,Optional.empty()); }
        public Completion { Objects.requireNonNull(result);Objects.requireNonNull(frame); }
    }
    private static final class Job {
        final long revision,reservation;final Request request;RenderMemory.Hold admission=RenderMemory.Hold.none();final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,long reservation,Request request) { this.revision=revision;this.reservation=reservation;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final RenderMemory memory;private final long workingBytes;private long revision;private boolean closed,scheduled;private Job active,pending;private Completion completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public WorldPickWorker() { this(1024L*1024*1024,null); }
    public WorldPickWorker(RenderMemory memory) { this(1024L*1024*1024,memory); }
    public WorldPickWorker(long workingBytes,RenderMemory memory) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Pointer query worker exceeds 1 GiB");
        this.workingBytes=workingBytes;this.memory=memory;executor=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-world-pick");thread.setDaemon(true);return thread;});
    }
    public Optional<RenderMemory> memory() { return Optional.ofNullable(memory); }
    private static Status state(Job job,Phase phase,List<WorldProblem> problems) { return new Status(job.revision,phase,Optional.of(job.request.index().id()),problems,job.request.frame()); }
    private long next() { if(closed)throw new IllegalStateException("Picking worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Picking revision counter exhausted");return revision+1; }
    private void cancelOwned() { if(active!=null)active.cancelled.set(true);if(pending!=null) { pending.cancelled.set(true);pending.admission.close();pending=null; }completed=null; }
    public long invalidate() { synchronized(lock) { long next=next();var waiting=new Status(next,Phase.WAITING,Optional.empty(),List.of());cancelOwned();revision=next;status=waiting;return next; } }
    public long submit(Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            long next=next(),reservation=request.index().queryPayloadBytes(request.displayed().orElse(null));
            if(reservation>workingBytes-(active==null?0:active.reservation))throw new IllegalStateException("Active and pending pointer queries exceed their combined allowance");
            var job=new Job(next,reservation,request);var queued=state(job,Phase.QUEUED,List.of());
            if(memory!=null)job.admission=memory.holdReplacing("Pointer picking request",reservation,pending==null?RenderMemory.Hold.none():pending.admission);
            cancelOwned();pending=job;revision=next;status=queued;
            if(!scheduled) { scheduled=true;try { executor.execute(this::drain); }catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;job.admission.close();status=failed(job,failure); } }return next;
        }
    }
    public Status status() { synchronized(lock) { return status; } }
    public long reservedBytes() { synchronized(lock) { return (active==null?0:active.reservation)+(pending==null?0:pending.reservation); } }
    public Optional<Completion> take(long latestRevision) {
        synchronized(lock) { return takeCurrent(latestRevision,Optional.empty()); }
    }
    public Optional<Completion> take(long latestRevision,Frame expectedFrame) {
        Objects.requireNonNull(expectedFrame);synchronized(lock) { return takeCurrent(latestRevision,Optional.of(expectedFrame)); }
    }
    private Optional<Completion> takeCurrent(long latestRevision,Optional<Frame> expectedFrame) {
        if(closed||latestRevision!=revision||completed==null||expectedFrame.isPresent()&&!expectedFrame.equals(completed.frame()))return Optional.empty();
        var value=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.indexId(),List.of(),status.frame());completed=null;status=delivered;return value;
    }
    private static Status failed(Job job,Throwable failure) { return state(job,Phase.FAILED,List.of(new WorldProblem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,Optional.empty(),"Picking request failed: "+failure.getClass().getSimpleName()))); }
    private void execute(Job job) {
        try {
            synchronized(lock) { if(!closed&&job.revision==revision&&!job.cancelled.get())status=state(job,Phase.PICKING,List.of()); }
            var request=job.request;var result=request.index().query(request.ray(),request.options(),request.displayed().orElse(null),job::cancelled);
            synchronized(lock) {
                if(!closed&&job.revision==revision&&!job.cancelled.get()) {
                    if(request.index().closed())status=failed(job,new CancellationException("Picking index closed before adoption"));
                    else if(result.requiresPlaceholder())status=state(job,Phase.FAILED,result.problems());
                    else { var value=new Completion(job.revision,result,request.frame());var ready=state(job,Phase.READY,List.of());completed=value;status=ready; }
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
