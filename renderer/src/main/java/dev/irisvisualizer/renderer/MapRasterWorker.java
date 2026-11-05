package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One active colorization and one latest pending request; never samples or regenerates a world. */
public final class MapRasterWorker implements AutoCloseable {
    public record Request(MapSamples samples,MapRaster.Style style,MapRaster.Limits limits,Optional<MapRaster> source) {
        public Request(MapSamples samples,MapRaster.Style style,MapRaster.Limits limits) { this(samples,style,limits,Optional.empty()); }
        public Request { Objects.requireNonNull(samples);Objects.requireNonNull(style);Objects.requireNonNull(limits);Objects.requireNonNull(source);if(source.isPresent()&&(source.orElseThrow().requiresPlaceholder()||source.orElseThrow().samples()!=samples))throw new IllegalArgumentException("Map recoloring requires a complete raster with the exact native samples"); }
        public Request(MapSamples samples,MapRaster.Style style) { this(samples,style,MapRaster.Limits.DEFAULT); }
        public static Request recolor(MapRaster source,MapRaster.Style style,MapRaster.Limits limits) { Objects.requireNonNull(source);return new Request(source.samples(),style,limits,Optional.of(source)); }
        long reservation() { return source.isPresent()?MapRaster.recolorReservation(source.orElseThrow(),style):samples.requiresPlaceholder()?0:samples.payloadBytes()+samples.grid().count()*4; }
    }
    public enum Phase { IDLE, WAITING, QUEUED, RENDERING, RECOLORING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<MapSamples.Context> context,List<WorldProblem> problems) { public Status { Objects.requireNonNull(phase);Objects.requireNonNull(context);problems=List.copyOf(problems); } }
    public record Completion(long revision,MapRaster raster,RenderMemory.Hold admission) implements AutoCloseable {
        public Completion { Objects.requireNonNull(raster);Objects.requireNonNull(admission); }
        public Completion(long revision,MapRaster raster) { this(revision,raster,RenderMemory.Hold.none()); }
        public RenderInput<MapRaster> retainInput() { return RenderInput.own(raster,admission.retain()); }
        @Override public void close() { admission.close(); }
    }
    private static final class Job {
        final long revision;final Request request;RenderMemory.Hold admission=RenderMemory.Hold.none();long retainedBytes;final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,Request request) { this.revision=revision;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final long workingBytes;private final RenderMemory memory;
    private long revision;private boolean closed,scheduled,disposed;private Job active,pending;private Completion completed;private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public MapRasterWorker() { this(128L*1024*1024); }
    public MapRasterWorker(long workingBytes) { this(workingBytes,null); }
    public MapRasterWorker(long workingBytes,RenderMemory memory) { this(workingBytes,memory,MapRasterWorker::executor); }
    MapRasterWorker(long workingBytes,RenderMemory memory,Supplier<ExecutorService> executors) {
        if(workingBytes<0||workingBytes>128L*1024*1024)throw new IllegalArgumentException("Map worker budget exceeds its bound");this.workingBytes=workingBytes;this.memory=memory;
        executor=Objects.requireNonNull(Objects.requireNonNull(executors).get());
    }
    private static ExecutorService executor() { return Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-map-raster");thread.setDaemon(true);return thread;}); }
    private long next() { if(closed)throw new IllegalStateException("Map worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Map revision counter exhausted");return revision+1; }
    /** Invalidate as soon as native inputs become obsolete, even before replacement sampling finishes. */
    public long invalidate() {
        synchronized(lock) { long next=next();var waiting=new Status(next,Phase.WAITING,Optional.empty(),List.of());cancelOwned();revision=next;status=waiting;return next; }
    }
    public long submit(Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            long next=next(),reserved=active==null?0:active.request.reservation();
            if(request.reservation()>workingBytes-reserved)throw new IllegalStateException("Active and latest pending map payload/output reservations exceed the worker budget");
            var job=new Job(next,request);var queued=new Status(next,Phase.QUEUED,Optional.of(request.samples().context()),List.of());
            job.admission=memory==null?RenderMemory.Hold.none():memory.holdReplacing("Map colorization",request.reservation(),pending==null?RenderMemory.Hold.none():pending.admission,completed==null?RenderMemory.Hold.none():completed.admission());
            cancelOwned();pending=job;revision=next;status=queued;
            if(!scheduled) { scheduled=true;try { executor.execute(this::drain); }catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;job.admission.close();status=failed(next,request.samples().context(),failure); } }return next;
        }
    }
    /** Consumes a detached native map input after destination admission or rejection. */
    public long submit(RenderInput<Request> input) {
        Objects.requireNonNull(input);
        try(var source=input.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Tracked native map input requires the same map coordinator");
            return submit(source.value());
        }finally { input.close(); }
    }
    /** Consumes a retained raster owner after recolor admission or rejection. */
    public long recolor(RenderInput<MapRaster> input,MapRaster.Style style,MapRaster.Limits limits) {
        Objects.requireNonNull(input);
        try(var source=input.retain()) {
            if(source.admission().tracked()&&(memory==null||!source.admission().belongsTo(memory)))throw new IllegalArgumentException("Tracked map recoloring requires the same map coordinator");
            return submit(Request.recolor(source.value(),style,limits));
        }finally { input.close(); }
    }
    private void dropCompleted() { if(completed!=null) { completed.close();completed=null; } }
    private void cancelOwned() { if(active!=null)active.cancelled.set(true);if(pending!=null) { pending.cancelled.set(true);pending.admission.close();pending=null; }dropCompleted(); }
    public Status status() { synchronized(lock) { return status; } }
    /** Cancelled active stacks remain charged; transferred rasters are external owners. */
    public long reservedBytes() {
        synchronized(lock) { return (active==null?0:active.request.reservation())+(pending==null?0:pending.request.reservation())+(completed==null||active!=null&&active.revision==revision?0:completed.raster().payloadBytes()); }
    }
    public Optional<Completion> take(long latestRevision) {
        synchronized(lock) { if(closed||latestRevision!=revision||completed==null)return Optional.empty();var value=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.context(),List.of());completed=null;status=delivered;return value; }
    }
    private static Status failed(long revision,MapSamples.Context context,Throwable failure) { return new Status(revision,Phase.FAILED,Optional.of(context),List.of(new WorldProblem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,Optional.empty(),"Map colorization failed: "+failure.getClass().getSimpleName()))); }
    private void execute(Job job) {
        try {
            synchronized(lock) { if(!closed&&job.revision==revision&&!job.cancelled.get())status=new Status(job.revision,job.request.source().isPresent()?Phase.RECOLORING:Phase.RENDERING,Optional.of(job.request.samples().context()),List.of()); }
            var request=job.request;var result=request.source().isPresent()?MapRaster.recolor(request.source().orElseThrow(),request.style(),request.limits(),job::cancelled):MapRaster.render(request.samples(),request.style(),request.limits(),job::cancelled);
            synchronized(lock) {
                if(!closed&&job.revision==revision&&!job.cancelled.get()) {
                    if(!result.requiresPlaceholder()) { if(result.payloadBytes()>job.request.reservation())throw new IllegalStateException("Map output exceeds its admitted peak");var ready=new Status(revision,Phase.READY,Optional.of(result.samples().context()),List.of());var hold=job.admission.retain();Completion value;
                        try { value=new Completion(revision,result,hold); }catch(RuntimeException|OutOfMemoryError failure) { hold.close();throw failure; }
                        job.retainedBytes=result.payloadBytes();completed=value;status=ready; }
                    else status=new Status(revision,Phase.FAILED,Optional.of(result.samples().context()),result.problems());
                }
            }
        }catch(CancellationException cancelled) { /* Superseded inputs never publish a frame. */ }
        catch(RuntimeException|OutOfMemoryError failure) { synchronized(lock) { if(!closed&&job.revision==revision&&!job.cancelled.get()) { dropCompleted();status=failed(revision,job.request.samples().context(),failure); } } }
    }
    private void drain() {
        while(true) {
            Job job;
            synchronized(lock) { if(closed||pending==null) { active=null;scheduled=false;return; }job=pending;pending=null;active=job; }
            try { execute(job); }finally { synchronized(lock) { active=null;var hold=job.admission;long retained=job.retainedBytes;job=null;hold.shrinkTo(retained);hold.close(); } }
        }
    }
    /** Cancels without waiting for native/JVM work from a UI thread. No GL context is accessed. */
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
