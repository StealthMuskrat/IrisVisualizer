package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One active and one latest pending scalar display mesh, without native sampling on this executor. */
public final class NoiseSurfaceWorker implements AutoCloseable {
    public record Request(NoiseVolume volume,NoiseSurface.Style style,NoiseSurface.Limits limits) {
        public Request {
            Objects.requireNonNull(volume);Objects.requireNonNull(style);Objects.requireNonNull(limits);
            if(volume.requiresPlaceholder()||volume.payloadBytes()>limits.workingBytes())throw new IllegalArgumentException("A complete scalar source within the working bound is required");
        }
        public long reservation() { return Math.min(limits.workingBytes(),volume.payloadBytes()+(long)limits.faces()*5); }
    }
    public enum Phase { IDLE, WAITING, QUEUED, MESHING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<UUID> volumeId,List<WorldProblem> problems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(volumeId);problems=List.copyOf(problems); }
    }
    public record Completion(long revision,RenderInput<NoiseSurface> input) implements AutoCloseable {
        public Completion { Objects.requireNonNull(input); }
        public NoiseSurface surface() { return input.value(); }
        @Override public void close() { input.close(); }
    }
    private static final class Job {
        final long revision;final Request request;final AtomicBoolean cancelled=new AtomicBoolean();RenderMemory.Hold admission=RenderMemory.Hold.none();long retainedBytes;
        Job(long revision,Request request) { this.revision=revision;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final RenderMemory memory;private final long workingBytes;
    private long revision,completedBytes;private boolean closed,scheduled;private Job active,pending;private Completion completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public NoiseSurfaceWorker() { this(1024L*1024*1024,null); }
    public NoiseSurfaceWorker(long workingBytes,RenderMemory memory) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Scalar mesh worker exceeds its combined allowance");this.workingBytes=workingBytes;this.memory=memory;
        executor=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-noise-surfaces");thread.setDaemon(true);return thread;});
    }
    private long next() { if(closed)throw new IllegalStateException("Scalar mesh worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Scalar mesh revision exhausted");return revision+1; }
    private boolean current(Job job) { return !closed&&job.revision==revision&&!job.cancelled.get(); }
    private static Status state(Job job,Phase phase,List<WorldProblem> problems) { return new Status(job.revision,phase,Optional.of(job.request.volume().id()),problems); }
    private static Status failed(Job job,Throwable failure) { return state(job,Phase.FAILED,List.of(new WorldProblem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,Optional.empty(),"Scalar surface preparation failed: "+failure.getClass().getSimpleName()))); }
    private void dropCompleted() { if(completed!=null) { completed.close();completed=null; }completedBytes=0; }
    private void cancelOwned() { if(active!=null)active.cancelled.set(true);if(pending!=null) { pending.cancelled.set(true);pending.admission.close();pending=null; }dropCompleted(); }
    /** Rejected admission preserves all prior work and revision. Active cancellation stays charged until unwind. */
    public long submit(Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            long next=next(),activeBytes=active==null?0:active.request.reservation();if(request.reservation()>workingBytes-activeBytes)throw new IllegalStateException("Active and pending scalar meshes exceed their combined working bound");
            var job=new Job(next,request);var queued=state(job,Phase.QUEUED,List.of());
            job.admission=memory==null?RenderMemory.Hold.none():memory.holdReplacing("Scalar surface preparation",request.reservation(),pending==null?RenderMemory.Hold.none():pending.admission,completed==null?RenderMemory.Hold.none():completed.input().admission());
            cancelOwned();pending=job;revision=next;status=queued;
            if(!scheduled) { scheduled=true;try { executor.execute(this::drain); }catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;job.admission.close();status=failed(job,failure); } }return next;
        }
    }
    /** Consumes a captured scalar owner after destination admission, including all rejection/failure outcomes. */
    public long submit(RenderInput<NoiseVolume> input,NoiseSurface.Style style,NoiseSurface.Limits limits) {
        Objects.requireNonNull(input);
        try(var source=input.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Scalar input requires the same managed coordinator");
            return submit(new Request(source.value(),style,limits));
        }finally { input.close(); }
    }
    public long invalidate() { synchronized(lock) { long next=next();var waiting=new Status(next,Phase.WAITING,Optional.empty(),List.of());cancelOwned();revision=next;status=waiting;return next; } }
    public Status status() { synchronized(lock) { return status; } }
    public long reservedBytes() { synchronized(lock) { return (active==null?0:active.request.reservation())+(pending==null?0:pending.request.reservation())+(completed==null||active!=null&&active.revision==completed.revision()?0:completedBytes); } }
    public Optional<Completion> take(long expectedRevision) {
        synchronized(lock) { if(closed||expectedRevision!=revision||completed==null)return Optional.empty();var value=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.volumeId(),List.of());completed=null;completedBytes=0;status=delivered;return value; }
    }
    private void execute(Job job) {
        RenderInput<NoiseSurface> output=null;
        try {
            synchronized(lock) { if(current(job))status=state(job,Phase.MESHING,List.of()); }
            var result=NoiseSurface.prepare(job.request.volume(),job.request.style(),job.request.limits(),job::cancelled);
            synchronized(lock) {
                if(current(job)) {
                    if(result.requiresPlaceholder())status=state(job,Phase.FAILED,result.problems());
                    else {
                        if(result.payloadBytes()>job.request.reservation())throw new IllegalStateException("Scalar output exceeds its admitted peak");
                        output=RenderInput.own(result,job.admission.retain());var complete=new Completion(job.revision,output);var ready=state(job,Phase.READY,List.of());
                        job.retainedBytes=result.payloadBytes();completed=complete;completedBytes=job.retainedBytes;status=ready;output=null;
                    }
                }
            }
        }catch(CancellationException cancellation) { /* Obsolete displays do not publish. */ }
        catch(RuntimeException|OutOfMemoryError failure) { synchronized(lock) { if(current(job)) { dropCompleted();status=failed(job,failure); } } }
        finally { if(output!=null)output.close(); }
    }
    private void drain() {
        while(true) {
            Job job;synchronized(lock) { if(closed||pending==null) { active=null;scheduled=false;return; }job=pending;pending=null;active=job; }
            try { execute(job); }finally { synchronized(lock) { active=null;job.admission.shrinkTo(job.retainedBytes);job.admission.close();job=null; } }
        }
    }
    @Override public void close() { synchronized(lock) { if(closed)return;var done=new Status(revision,Phase.CLOSED,Optional.empty(),List.of());closed=true;cancelOwned();status=done; }executor.shutdownNow(); }
}
