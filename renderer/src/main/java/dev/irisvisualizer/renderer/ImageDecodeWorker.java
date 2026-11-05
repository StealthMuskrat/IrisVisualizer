package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One active PNG/JPEG decode and one latest pending request; immutable original pixels only. */
public final class ImageDecodeWorker implements AutoCloseable {
    public record Request(SourceImage.Encoded source,SourceImage.Limits limits) {
        public Request { Objects.requireNonNull(source);Objects.requireNonNull(limits); }
        public Request(SourceImage.Encoded source) { this(source,SourceImage.Limits.DEFAULT); }
        long reservation() { return SourceImage.reservation(source,limits); }
    }
    public enum Phase { IDLE, WAITING, QUEUED, DECODING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<SourceImage.Identity> source,List<SourceImage.Problem> problems) { public Status { Objects.requireNonNull(phase);Objects.requireNonNull(source);problems=List.copyOf(problems); } }
    public record Completion(long revision,SourceImage image,RenderMemory.Hold admission) implements AutoCloseable {
        public Completion { if(revision<1)throw new IllegalArgumentException("Invalid image revision");Objects.requireNonNull(image);Objects.requireNonNull(admission); }
        public Completion(long revision,SourceImage image) { this(revision,image,RenderMemory.Hold.none()); }
        public RenderInput<SourceImage> retainInput() { return RenderInput.own(image,admission.retain()); }
        @Override public void close() { admission.close(); }
    }
    private static final class Job {
        final long revision;final Request request;RenderMemory.Hold admission=RenderMemory.Hold.none();long retainedBytes;final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,Request request) { this.revision=revision;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final long workingBytes;private final RenderMemory memory;
    private long revision;private boolean closed,scheduled,disposed;private Job active,pending;private Completion completed;private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public ImageDecodeWorker() { this(256L*1024*1024); }
    public ImageDecodeWorker(long workingBytes) { this(workingBytes,null); }
    public ImageDecodeWorker(long workingBytes,RenderMemory memory) { this(workingBytes,memory,ImageDecodeWorker::executor); }
    ImageDecodeWorker(long workingBytes,RenderMemory memory,Supplier<ExecutorService> executors) {
        if(workingBytes<0||workingBytes>256L*1024*1024)throw new IllegalArgumentException("Image worker budget exceeds its bound");this.workingBytes=workingBytes;this.memory=memory;
        executor=Objects.requireNonNull(Objects.requireNonNull(executors).get());
    }
    private static ExecutorService executor() { return Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-image-decode");thread.setDaemon(true);return thread;}); }
    private long next() { if(closed)throw new IllegalStateException("Image worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Image revision counter exhausted");return revision+1; }
    /** Invalidate as soon as image inputs become obsolete, before replacement bytes arrive. */
    public long invalidate() {
        synchronized(lock) { long next=next();var waiting=new Status(next,Phase.WAITING,Optional.empty(),List.of());cancelOwned();revision=next;status=waiting;return next; }
    }
    public long submit(Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            long next=next(),reserved=active==null?0:active.request.reservation();
            if(request.reservation()>workingBytes-reserved)throw new IllegalStateException("Active and latest pending image source/output reservations exceed the worker budget");
            var job=new Job(next,request);var queued=new Status(next,Phase.QUEUED,Optional.of(request.source().identity()),List.of());
            job.admission=memory==null?RenderMemory.Hold.none():memory.holdReplacing("Original image decoding",request.reservation(),pending==null?RenderMemory.Hold.none():pending.admission,completed==null?RenderMemory.Hold.none():completed.admission());
            cancelOwned();pending=job;revision=next;status=queued;
            if(!scheduled) { scheduled=true;try { executor.execute(this::drain); }catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;job.admission.close();status=failed(next,request.source().identity(),failure); } }return next;
        }
    }
    /** Consumes a detached image input after destination admission or rejection. */
    public long submit(RenderInput<Request> input) {
        Objects.requireNonNull(input);
        try(input;var source=input.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Tracked image input requires the same image coordinator");
            return submit(source.value());
        }
    }
    private void dropCompleted() { if(completed!=null) { completed.close();completed=null; } }
    private void cancelOwned() { if(active!=null)active.cancelled.set(true);if(pending!=null) { pending.cancelled.set(true);pending.admission.close();pending=null; }dropCompleted(); }
    public Status status() { synchronized(lock) { return status; } }
    /** Cancelled active stacks remain charged; transferred images are external owners. */
    public long reservedBytes() {
        synchronized(lock) { return (active==null?0:active.request.reservation())+(pending==null?0:pending.request.reservation())+(completed==null||active!=null&&active.revision==revision?0:completed.image().payloadBytes()); }
    }
    public Optional<Completion> take(long latestRevision) {
        synchronized(lock) { if(closed||latestRevision!=revision||completed==null)return Optional.empty();var value=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.source(),List.of());completed=null;status=delivered;return value; }
    }
    private static Status failed(long revision,SourceImage.Identity context,Throwable failure) { return new Status(revision,Phase.FAILED,Optional.of(context),List.of(new SourceImage.Problem(failure instanceof OutOfMemoryError?SourceImage.Code.MEMORY:SourceImage.Code.WORKER,context,"Original image decoding failed: "+failure.getClass().getSimpleName()))); }
    private void execute(Job job) {
        try {
            synchronized(lock) { if(!closed&&job.revision==revision&&!job.cancelled.get())status=new Status(job.revision,Phase.DECODING,Optional.of(job.request.source().identity()),List.of()); }
            var request=job.request;var result=SourceImage.decode(request.source(),request.limits(),job::cancelled);
            synchronized(lock) {
                if(!closed&&job.revision==revision&&!job.cancelled.get()) {
                    if(!result.requiresPlaceholder()) { var ready=new Status(revision,Phase.READY,Optional.of(result.identity()),List.of());var hold=job.admission.retain();Completion value;
                        try { value=new Completion(revision,result,hold); }catch(RuntimeException|OutOfMemoryError failure) { hold.close();throw failure; }
                        job.retainedBytes=result.payloadBytes();completed=value;status=ready; }
                    else status=new Status(revision,Phase.FAILED,Optional.of(result.identity()),result.problems());
                }
            }
        }catch(CancellationException cancelled) { /* Superseded inputs never publish a frame. */ }
        catch(RuntimeException|OutOfMemoryError failure) { synchronized(lock) { if(!closed&&job.revision==revision&&!job.cancelled.get()) { dropCompleted();status=failed(revision,job.request.source().identity(),failure); } } }
    }
    private void drain() {
        while(true) {
            Job job;
            synchronized(lock) { if(closed||pending==null) { active=null;scheduled=false;return; }job=pending;pending=null;active=job; }
            try { execute(job); }finally { synchronized(lock) { active=null;var hold=job.admission;long retained=job.retainedBytes;job=null;hold.shrinkTo(retained);hold.close(); } }
        }
    }
    /** Cancels without waiting for decoder/JVM work from a UI thread. No GL context is accessed. */
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
