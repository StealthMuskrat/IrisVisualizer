package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One active CPU scene job and one replaceable pending request. No UI or GPU thread is called. */
public final class WorldSceneWorker implements AutoCloseable {
    public record Request(WorldModels models,WorldVisibility visibility,AtlasRaster raster,WorldScenePlan.Limits sceneLimits,WorldMesher.Limits sectionLimits) {
        public Request {
            Objects.requireNonNull(models);Objects.requireNonNull(visibility);Objects.requireNonNull(raster);Objects.requireNonNull(sceneLimits);Objects.requireNonNull(sectionLimits);
            if(visibility.snapshot()!=models.snapshot()||visibility.layout()!=models.layout()||raster.layout()!=models.layout())throw new IllegalArgumentException("Standalone scene inputs require the exact native snapshot and atlas epoch");
        }
        public Request(WorldModels models,WorldVisibility visibility,AtlasRaster raster) { this(models,visibility,raster,WorldScenePlan.Limits.DEFAULT,WorldMesher.Limits.DEFAULT); }
        /** Conservative source payload shared with scene planning; no output or native query is performed. */
        public long inputBytes() {
            long atlas=models.layout().usage().map(usage->usage.knownPayloadBytes()-usage.boundMeshBytes()).orElse(0L);
            return models.snapshot().indexBytes()+models.indexBytes()+models.offsets().bytes()+models.tints().bytes()+visibility.bytes()+models.meshBytes()+atlas;
        }
    }
    public enum Phase { IDLE, QUEUED, PLANNING, BUILDING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<WorldSnapshot.Source> source,List<WorldProblem> problems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(source);problems=List.copyOf(problems); }
    }
    /** The caller owns the transferred result and must close it before replacing its scene epoch. */
    public record Completion(long revision,WorldScene.Result result) { public Completion { Objects.requireNonNull(result); } }
    private static final class Job {
        final long revision;final Request request;long admissionBytes;final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,Request request) { this.revision=revision;this.request=request;admissionBytes=request.sceneLimits().workingBytes(); }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final WorldSectionCache cache;private final ExecutorService executor;private final RenderMemory.Account admission;private final RenderMemory memory;private final long workingBytes;
    private long revision;private boolean closed,scheduled;private Job pending,active;private WorldScene.Result completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),List.of());
    public WorldSceneWorker() { this(new WorldSectionCache()); }
    /** Existing externally configured caches retain their own admission policy. */
    public WorldSceneWorker(WorldSectionCache cache) {
        this.cache=Objects.requireNonNull(cache);admission=null;memory=null;workingBytes=1024L*1024*1024;executor=executor();
    }
    /** Owns one shared cache/job account, including externally retained scene tickets after close. */
    public WorldSceneWorker(long workingBytes,RenderMemory memory) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Standalone scene worker limit exceeds 1 GiB");this.workingBytes=workingBytes;this.memory=memory;
        admission=memory==null?null:memory.open("Standalone scene preparation and cache");
        try { cache=new WorldSectionCache(WorldSectionCache.Limits.DEFAULT,admission);executor=executor(); }
        catch(RuntimeException|OutOfMemoryError failure) { if(admission!=null)admission.close();throw failure; }
    }
    private static ExecutorService executor() { return Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-world-scene");thread.setDaemon(true);return thread;}); }
    private long next() { if(closed)throw new IllegalStateException("Scene worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Scene revision counter exhausted");return revision+1; }
    private void reserve(long bytes) { if(admission!=null)admission.reserve(bytes); }
    private void refreshAdmission() {
        long running=active==null?0:active.admissionBytes,input=pending==null?0:pending.request.inputBytes();
        if(active==null&&pending!=null)running=cache.stats().knownPayloadBytes();
        reserve(Math.max(running+input,pending==null?0:pending.request.sceneLimits().workingBytes()));
    }
    private void dropCompleted() { if(completed!=null) { completed.close();completed=null; } }
    private void cancelOwned() { if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);pending=null;dropCompleted(); }
    /** Admission precedes cancellation, revision changes or closing worker-owned output. */
    public long submit(Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            long next=next();long input=request.inputBytes(),running=active==null?cache.stats().knownPayloadBytes():active.admissionBytes;
            long reservation=Math.max(request.sceneLimits().workingBytes(),running+input);
            if(reservation>workingBytes)throw new IllegalArgumentException("Standalone scene/cache and pending input exceed their aggregate budget");
            var job=new Job(next,request);var queued=status(job,Phase.QUEUED,List.of());reserve(reservation);
            cancelOwned();pending=job;revision=next;status=queued;
            if(!scheduled) {
                scheduled=true;
                try { executor.execute(this::drain); }
                catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;refreshAdmission();status=status(job,Phase.FAILED,List.of(problem(failure,"The CPU scene worker could not be started"))); }
            }
            return next;
        }
    }
    /** Consumes a visual completion on every outcome; destination admission precedes releasing its hold. */
    public long submit(WorldVisualWorker.Completion prepared,WorldVisibility visibility) { return submit(prepared,visibility,WorldScenePlan.Limits.DEFAULT,WorldMesher.Limits.DEFAULT); }
    public long submit(WorldVisualWorker.Completion prepared,WorldVisibility visibility,WorldScenePlan.Limits sceneLimits,WorldMesher.Limits sectionLimits) {
        Objects.requireNonNull(prepared);
        try(var sourceAdmission=prepared.admission().retain()) {
            if(prepared.admission().tracked()&&(admission==null||!prepared.admission().belongsTo(memory)))throw new IllegalArgumentException("Tracked visual handoff requires the same managed scene coordinator");
            var result=prepared.result();if(result.requiresPlaceholder())throw new IllegalArgumentException("A successful visual result is required for scene handoff");
            return submit(new Request(result.models().orElseThrow(),visibility,result.raster().orElseThrow(),sceneLimits,sectionLimits));
        }finally { prepared.close(); }
    }
    public long invalidate() { synchronized(lock) { long next=next();var waiting=new Status(next,Phase.IDLE,Optional.empty(),List.of());cancelOwned();refreshAdmission();revision=next;status=waiting;return next; } }
    public Status status() { synchronized(lock) { return status; } }
    public WorldSectionCache.Stats cacheStats() { return cache.stats(); }
    public Optional<RenderMemory.Owner> memoryUsage() { synchronized(lock) { return admission==null?Optional.empty():Optional.of(admission.stats()); } }
    /** Legacy current-revision transfer; use the token overload for explicit stale-result rejection. */
    public Optional<Completion> take() { synchronized(lock) { return take(revision); } }
    public Optional<Completion> take(long expectedRevision) {
        synchronized(lock) {
            if(closed||expectedRevision!=revision||completed==null)return Optional.empty();var completion=Optional.of(new Completion(revision,completed));var delivered=new Status(revision,Phase.DELIVERED,status.source(),List.of());completed=null;status=delivered;return completion;
        }
    }
    private static WorldProblem problem(Throwable failure,String reason) { return new WorldProblem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,Optional.empty(),reason+": "+failure.getClass().getSimpleName()); }
    private static Status status(Job job,Phase phase,List<WorldProblem> problems) { return new Status(job.revision,phase,Optional.of(job.request.models().snapshot().source()),problems); }
    private void phase(Job job,Phase phase) { synchronized(lock) { if(!closed&&job.revision==revision&&!job.cancelled.get())status=status(job,phase,List.of()); } }
    private void drain() {
        try {
            while(true) {
                Job job;synchronized(lock) {
                    if(closed||pending==null) { active=null;scheduled=false;refreshAdmission();return; }
                    job=pending;job.admissionBytes=Math.max(job.request.sceneLimits().workingBytes(),cache.stats().knownPayloadBytes()+job.request.inputBytes());pending=null;active=job;refreshAdmission();
                }
                try { execute(job); }
                finally { synchronized(lock) { active=null;job=null;refreshAdmission(); } }
            }
        }catch(RuntimeException|Error failure) { synchronized(lock) { active=null;scheduled=false;refreshAdmission(); }throw failure; }
        finally { synchronized(lock) { if(closed)cache.close(); } }
    }
    private void execute(Job job) {
        WorldScene.Result result=null;List<WorldProblem> failure=List.of();boolean retained=false;
        try {
            try {
                phase(job,Phase.PLANNING);var request=job.request;var plan=WorldScenePlan.plan(request.models(),request.visibility(),request.sceneLimits(),request.sectionLimits(),job::cancelled);
                if(plan.requiresPlaceholder())result=WorldScene.Result.failed(plan.snapshot().source(),plan.problems());
                else { phase(job,Phase.BUILDING);result=WorldScene.build(plan,request.raster(),cache,job::cancelled); }
            }catch(CancellationException cancelled) { /* Superseded jobs never replace newer output. */ }
            catch(RuntimeException|OutOfMemoryError unexpected) { failure=List.of(problem(unexpected,"CPU scene assembly failed")); }
            synchronized(lock) {
                if(!closed&&job.revision==revision&&!job.cancelled.get()) {
                    if(result!=null&&!result.requiresPlaceholder()) { var ready=status(job,Phase.READY,List.of());completed=result;retained=true;status=ready; }
                    else { var problems=result!=null?result.problems():failure;if(problems.isEmpty())problems=List.of(new WorldProblem(WorldProblem.Code.WORKER,Optional.empty(),"CPU scene request ended without a complete result"));status=status(job,Phase.FAILED,problems); }
                }
            }
        }finally { if(!retained&&result!=null)result.close(); }
    }
    /** Cancels cooperatively; external scene leases keep the cache admission until their last release. */
    @Override public void close() {
        synchronized(lock) { if(closed)return;var done=new Status(revision,Phase.CLOSED,Optional.empty(),List.of());closed=true;cancelOwned();refreshAdmission();status=done; }
        executor.shutdownNow();cache.close();
    }
}
