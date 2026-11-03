package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One active colorization and one latest pending request; never samples or regenerates a world. */
public final class MapRasterWorker implements AutoCloseable {
    public record Request(MapSamples samples,MapRaster.Style style,MapRaster.Limits limits) {
        public Request { Objects.requireNonNull(samples);Objects.requireNonNull(style);Objects.requireNonNull(limits); }
        public Request(MapSamples samples,MapRaster.Style style) { this(samples,style,MapRaster.Limits.DEFAULT); }
        long reservation() { return samples.requiresPlaceholder()?0:samples.payloadBytes()+samples.grid().count()*4; }
    }
    public enum Phase { IDLE, WAITING, QUEUED, RENDERING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<MapSamples.Context> context,List<WorldProblem> problems) { public Status { Objects.requireNonNull(phase);Objects.requireNonNull(context);problems=List.copyOf(problems); } }
    public record Completion(long revision,MapRaster raster) { public Completion { Objects.requireNonNull(raster); } }
    private static final class Job {
        final long revision;final Request request;final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,Request request) { this.revision=revision;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final long workingBytes;
    private long revision;private boolean closed,scheduled;private Job active,pending;private MapRaster completed;private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public MapRasterWorker() { this(128L*1024*1024); }
    public MapRasterWorker(long workingBytes) {
        if(workingBytes<0||workingBytes>128L*1024*1024)throw new IllegalArgumentException("Map worker budget exceeds its bound");this.workingBytes=workingBytes;
        executor=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-map-raster");thread.setDaemon(true);return thread;});
    }
    private long next() { if(closed)throw new IllegalStateException("Map worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Map revision counter exhausted");return revision+1; }
    /** Invalidate as soon as native inputs become obsolete, even before replacement sampling finishes. */
    public long invalidate() {
        synchronized(lock) { long next=next();var waiting=new Status(next,Phase.WAITING,Optional.empty(),List.of());cancelOwned();revision=next;status=waiting;return next; }
    }
    public long submit(Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            long next=next(),reserved=active==null?0:active.request.reservation();
            if(request.reservation()>workingBytes-reserved)throw new IllegalStateException("Active and latest pending map payload/output reservations exceed the worker budget");
            var job=new Job(next,request);var queued=new Status(next,Phase.QUEUED,Optional.of(request.samples().context()),List.of());cancelOwned();pending=job;revision=next;status=queued;
            if(!scheduled) { scheduled=true;try { executor.execute(this::drain); }catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;status=failed(next,request.samples().context(),failure); } }return next;
        }
    }
    private void cancelOwned() { if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);pending=null;completed=null; }
    public Status status() { synchronized(lock) { return status; } }
    public Optional<Completion> take(long latestRevision) {
        synchronized(lock) { if(closed||latestRevision!=revision||completed==null)return Optional.empty();var value=Optional.of(new Completion(revision,completed));var delivered=new Status(revision,Phase.DELIVERED,status.context(),List.of());completed=null;status=delivered;return value; }
    }
    private static Status failed(long revision,MapSamples.Context context,Throwable failure) { return new Status(revision,Phase.FAILED,Optional.of(context),List.of(new WorldProblem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,Optional.empty(),"Map colorization failed: "+failure.getClass().getSimpleName()))); }
    private void execute(Job job) {
        try {
            synchronized(lock) { if(!closed&&job.revision==revision&&!job.cancelled.get())status=new Status(job.revision,Phase.RENDERING,Optional.of(job.request.samples().context()),List.of()); }
            var request=job.request;var result=MapRaster.render(request.samples(),request.style(),request.limits(),job::cancelled);
            synchronized(lock) {
                if(!closed&&job.revision==revision&&!job.cancelled.get()) {
                    if(!result.requiresPlaceholder()) { var ready=new Status(revision,Phase.READY,Optional.of(result.samples().context()),List.of());completed=result;status=ready; }
                    else status=new Status(revision,Phase.FAILED,Optional.of(result.samples().context()),result.problems());
                }
            }
        }catch(CancellationException cancelled) { /* Superseded inputs never publish a frame. */ }
        catch(RuntimeException|OutOfMemoryError failure) { synchronized(lock) { if(!closed&&job.revision==revision&&!job.cancelled.get()) { completed=null;status=failed(revision,job.request.samples().context(),failure); } } }
    }
    private void drain() {
        while(true) {
            Job job;
            synchronized(lock) { if(closed||pending==null) { active=null;scheduled=false;return; }job=pending;pending=null;active=job; }
            try { execute(job); }finally { synchronized(lock) { active=null; } }
        }
    }
    /** Cancels without waiting for native/JVM work from a UI thread. No GL context is accessed. */
    @Override public void close() { synchronized(lock) { if(closed)return;var done=new Status(revision,Phase.CLOSED,Optional.empty(),List.of());closed=true;cancelOwned();status=done; }executor.shutdownNow(); }
}
