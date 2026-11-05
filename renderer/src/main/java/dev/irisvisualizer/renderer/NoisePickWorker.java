package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One active scalar query and one latest pending pointer. Completions retain no surface or GL owner. */
public final class NoisePickWorker implements AutoCloseable {
    /** Exact displayed-image identity, separate from source volume and worker revision. */
    public record Frame(UUID id,long revision) {
        public Frame {
            Objects.requireNonNull(id);
            if(revision<1)throw new IllegalArgumentException("Invalid scalar picking frame revision");
        }
    }
    public record Request(NoiseSurface surface,WorldRay ray,NoisePick.Options options,Optional<Frame> frame) {
        public Request {
            Objects.requireNonNull(surface);Objects.requireNonNull(ray);
            Objects.requireNonNull(options);Objects.requireNonNull(frame);
        }
        public Request(NoiseSurface surface,WorldRay ray,NoisePick.Options options) {
            this(surface,ray,options,Optional.empty());
        }
        public long reservation() { return surface.payloadBytes(); }
    }
    public enum Phase { IDLE, WAITING, QUEUED, PICKING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<UUID> volumeId,Optional<Frame> frame,List<WorldProblem> problems) {
        public Status {
            Objects.requireNonNull(phase);Objects.requireNonNull(volumeId);
            Objects.requireNonNull(frame);problems=List.copyOf(problems);
        }
    }
    public record Completion(long revision,Optional<Frame> frame,NoisePick.Result result) {
        public Completion { Objects.requireNonNull(frame);Objects.requireNonNull(result); }
    }
    private static final class Job {
        final long revision;
        final Request request;
        final AtomicBoolean cancelled=new AtomicBoolean();
        RenderMemory.Hold admission=RenderMemory.Hold.none();
        Job(long revision,Request request) { this.revision=revision;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();
    private final ExecutorService executor;
    private final RenderMemory memory;
    private final long workingBytes;
    private long revision;
    private boolean closed,scheduled,disposed;
    private Job active,pending;
    private Completion completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),Optional.empty(),List.of());

    public NoisePickWorker() { this(1024L*1024*1024,null); }
    public NoisePickWorker(RenderMemory memory) { this(1024L*1024*1024,memory); }
    public NoisePickWorker(long workingBytes,RenderMemory memory) { this(workingBytes,memory,NoisePickWorker::executor); }
    NoisePickWorker(long workingBytes,RenderMemory memory,Supplier<ExecutorService> executors) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)
            throw new IllegalArgumentException("Scalar query worker exceeds its combined allowance");
        this.workingBytes=workingBytes;this.memory=memory;
        executor=Objects.requireNonNull(Objects.requireNonNull(executors).get());
    }
    private static ExecutorService executor() { return Executors.newSingleThreadExecutor(task->{
            var thread=new Thread(task,"iris-noise-pick");thread.setDaemon(true);return thread;
        }); }
    public Optional<RenderMemory> memory() { return Optional.ofNullable(memory); }
    private long next() {
        if(closed)throw new IllegalStateException("Scalar picking worker is closed");
        if(revision==Long.MAX_VALUE)throw new IllegalStateException("Scalar picking revision exhausted");
        return revision+1;
    }
    private boolean current(Job job) { return !closed&&job.revision==revision&&!job.cancelled.get(); }
    private static Status state(Job job,Phase phase,List<WorldProblem> problems) {
        return new Status(job.revision,phase,Optional.of(job.request.surface().volume().id()),job.request.frame(),problems);
    }
    private static Status failed(Job job,Throwable failure) {
        return state(job,Phase.FAILED,List.of(new WorldProblem(
                failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,
                Optional.empty(),"Scalar picking request failed: "+failure.getClass().getSimpleName())));
    }
    private void cancelOwned() {
        if(active!=null)active.cancelled.set(true);
        if(pending!=null) { pending.cancelled.set(true);pending.admission.close();pending=null; }
        completed=null;
    }
    /** Admission rejection leaves earlier work, completion and revision intact. Active work cannot supply credit. */
    public long submit(Request request) {
        Objects.requireNonNull(request);
        synchronized(lock) {
            long next=next(),activeBytes=active==null?0:active.request.reservation();
            if(request.reservation()>workingBytes-activeBytes)
                throw new IllegalStateException("Active and pending scalar queries exceed their combined working allowance");
            var job=new Job(next,request);
            var queued=state(job,Phase.QUEUED,List.of());
            if(memory!=null)job.admission=memory.holdReplacing("Scalar pointer query",request.reservation(),
                    pending==null?RenderMemory.Hold.none():pending.admission);
            cancelOwned();pending=job;revision=next;status=queued;
            if(!scheduled) {
                scheduled=true;
                try { executor.execute(this::drain); }
                catch(RuntimeException|OutOfMemoryError failure) {
                    scheduled=false;pending=null;job.admission.close();status=failed(job,failure);
                }
            }
            return next;
        }
    }
    /** Consume owned CPU input after destination admission, on success and rejection alike. */
    public long submit(RenderInput<Request> input) {
        Objects.requireNonNull(input);
        try(input;var source=input.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))
                throw new IllegalArgumentException("Scalar picking input requires the same shared coordinator");
            return submit(source.value());
        }
    }
    /** Use when the pointer leaves, or the displayed image changes before a replacement query is available. */
    public long invalidate() {
        synchronized(lock) {
            long next=next();var waiting=new Status(next,Phase.WAITING,Optional.empty(),Optional.empty(),List.of());
            cancelOwned();revision=next;status=waiting;return next;
        }
    }
    public Status status() { synchronized(lock) { return status; } }
    public long reservedBytes() {
        synchronized(lock) { return (active==null?0:active.request.reservation())+(pending==null?0:pending.request.reservation()); }
    }
    public Optional<Completion> take(long expectedRevision) {
        synchronized(lock) { return takeCurrent(expectedRevision,Optional.empty()); }
    }
    /** A mismatched displayed image never consumes a completion. Compare UUID as well as display revision. */
    public Optional<Completion> take(long expectedRevision,Frame expectedFrame) {
        Objects.requireNonNull(expectedFrame);
        synchronized(lock) { return takeCurrent(expectedRevision,Optional.of(expectedFrame)); }
    }
    private Optional<Completion> takeCurrent(long expectedRevision,Optional<Frame> expectedFrame) {
        if(closed||expectedRevision!=revision||completed==null||
                expectedFrame.isPresent()&&!expectedFrame.equals(completed.frame()))return Optional.empty();
        var result=Optional.of(completed);
        var delivered=new Status(revision,Phase.DELIVERED,status.volumeId(),status.frame(),List.of());
        completed=null;status=delivered;return result;
    }
    private void execute(Job job) {
        try {
            synchronized(lock) { if(current(job))status=state(job,Phase.PICKING,List.of()); }
            WorldProblem.check(job::cancelled);
            var request=job.request;
            var result=NoisePick.query(request.surface(),request.ray(),request.options(),job::cancelled);
            synchronized(lock) {
                if(current(job)) {
                    if(result.requiresPlaceholder())status=state(job,Phase.FAILED,result.problems());
                    else {
                        var completion=new Completion(job.revision,request.frame(),result);
                        var ready=state(job,Phase.READY,List.of());completed=completion;status=ready;
                    }
                }
            }
        }catch(RuntimeException|OutOfMemoryError failure) {
            synchronized(lock) { if(current(job)) { completed=null;status=failed(job,failure); } }
        }
    }
    private void drain() {
        while(true) {
            Job job;
            synchronized(lock) {
                if(closed||pending==null) { active=null;scheduled=false;return; }
                job=pending;pending=null;active=job;
            }
            try { execute(job); }
            finally { synchronized(lock) { active=null;var hold=job.admission;job=null;hold.close(); } }
        }
    }
    /** Nonblocking cancellation. Active input stays charged until its CPU query has unwound. */
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
