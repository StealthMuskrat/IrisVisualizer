package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One active CPU scene job and one replaceable pending request. No UI or GPU thread is called. */
public final class WorldSceneWorker implements AutoCloseable {
    public record Request(WorldModels models,WorldVisibility visibility,AtlasRaster raster,WorldScenePlan.Limits sceneLimits,WorldMesher.Limits sectionLimits) {
        public Request { Objects.requireNonNull(models);Objects.requireNonNull(visibility);Objects.requireNonNull(raster);Objects.requireNonNull(sceneLimits);Objects.requireNonNull(sectionLimits); }
        public Request(WorldModels models,WorldVisibility visibility,AtlasRaster raster) { this(models,visibility,raster,WorldScenePlan.Limits.DEFAULT,WorldMesher.Limits.DEFAULT); }
    }
    public enum Phase { IDLE, QUEUED, PLANNING, BUILDING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<WorldSnapshot.Source> source,List<WorldProblem> problems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(source);problems=List.copyOf(problems); }
    }
    /** The caller owns the transferred result and must close it before replacing its scene epoch. */
    public record Completion(long revision,WorldScene.Result result) { public Completion { Objects.requireNonNull(result); } }
    private static final class Job {
        final long revision;final Request request;final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,Request request) { this.revision=revision;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final WorldSectionCache cache;private final ExecutorService executor;
    private long revision;private boolean closed,scheduled;private Job pending,active;private WorldScene.Result completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public WorldSceneWorker() { this(new WorldSectionCache()); }
    public WorldSceneWorker(WorldSectionCache cache) {
        this.cache=Objects.requireNonNull(cache);executor=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"iris-world-scene");thread.setDaemon(true);return thread;});
    }
    /** Caller-supplied immutable inputs are retained for at most one active and one pending job. */
    public long submit(Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            if(closed)throw new IllegalStateException("Scene worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Scene revision counter exhausted");
            long next=revision+1;var job=new Job(next,request);var queued=new Status(next,Phase.QUEUED,Optional.of(request.models().snapshot().source()),List.of());
            if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);pending=job;revision=next;
            if(completed!=null) { completed.close();completed=null; }
            status=queued;
            if(!scheduled) {
                scheduled=true;
                try { executor.execute(this::drain); }
                catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;status=new Status(next,Phase.FAILED,status.source(),List.of(new WorldProblem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,Optional.empty(),"The CPU scene worker could not be started"))); }
            }
            return next;
        }
    }
    public Status status() { synchronized(lock) { return status; } }
    public WorldSectionCache.Stats cacheStats() { return cache.stats(); }
    /** No stale scene can be taken after submit/close. After transfer, the caller controls its lease. */
    public Optional<Completion> take() {
        synchronized(lock) {
            if(closed||completed==null)return Optional.empty();var completion=Optional.of(new Completion(revision,completed));var delivered=new Status(revision,Phase.DELIVERED,status.source(),List.of());completed=null;status=delivered;return completion;
        }
    }
    private void phase(Job job,Phase phase) { synchronized(lock) { if(!closed&&job.revision==revision&&!job.cancelled.get())status=new Status(revision,phase,Optional.of(job.request.models().snapshot().source()),List.of()); } }
    private void drain() {
        try {
            while(true) {
                Job job;
                synchronized(lock) {
                    if(closed||pending==null) { active=null;scheduled=false;return; }
                    job=pending;pending=null;active=job;status=new Status(job.revision,Phase.PLANNING,Optional.of(job.request.models().snapshot().source()),List.of());
                }
                WorldScene.Result result=null;List<WorldProblem> failure=List.of();
                try {
                    var request=job.request;var plan=WorldScenePlan.plan(request.models(),request.visibility(),request.sceneLimits(),request.sectionLimits(),job::cancelled);
                    if(plan.requiresPlaceholder())result=WorldScene.Result.failed(plan.snapshot().source(),plan.problems());
                    else { phase(job,Phase.BUILDING);result=WorldScene.build(plan,request.raster(),cache,job::cancelled); }
                } catch(CancellationException cancelled) { /* Superseded jobs never replace newer status/results. */ }
                catch(RuntimeException unexpected) { failure=List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"CPU scene assembly failed: "+unexpected.getClass().getSimpleName())); }
                catch(OutOfMemoryError exhausted) { failure=List.of(new WorldProblem(WorldProblem.Code.MEMORY,Optional.empty(),"Not enough memory to complete the CPU scene request")); }
                boolean retained=false;
                try {
                    synchronized(lock) {
                        if(!closed&&job.revision==revision&&!job.cancelled.get()) {
                            if(result!=null&&!result.requiresPlaceholder()) { completed=result;retained=true;status=new Status(revision,Phase.READY,Optional.of(result.source()),List.of()); }
                            else { var problems=result!=null?result.problems():failure;if(problems.isEmpty())problems=List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"CPU scene request ended without a complete result"));status=new Status(revision,Phase.FAILED,Optional.of(job.request.models().snapshot().source()),problems); }
                        }
                        active=null;
                    }
                } finally { if(!retained&&result!=null)result.close(); }
            }
        } finally {
            synchronized(lock) { if(closed)cache.close(); }
        }
    }
    /** Cancels cooperatively and drops owned results; does not wait on a worker from the UI thread. */
    @Override public void close() {
        synchronized(lock) {
            if(closed)return;closed=true;if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);pending=null;
            if(completed!=null) { completed.close();completed=null; }status=new Status(revision,Phase.CLOSED,Optional.empty(),List.of());
        }
        executor.shutdownNow();cache.close();
    }
}
