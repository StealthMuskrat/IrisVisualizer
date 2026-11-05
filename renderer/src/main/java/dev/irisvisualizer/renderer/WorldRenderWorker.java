package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Single CPU path from frozen native visual decisions to a complete leased world scene. */
public final class WorldRenderWorker implements AutoCloseable {
    public record Request(WorldVisuals.Request visuals,WorldVisibility.Input visibility,WorldScenePlan.Limits sceneLimits,WorldMesher.Limits sectionLimits,long workingBytes) {
        public Request {
            Objects.requireNonNull(visuals);Objects.requireNonNull(visibility);Objects.requireNonNull(sceneLimits);Objects.requireNonNull(sectionLimits);
            if(visibility.snapshot()!=visuals.snapshot())throw new IllegalArgumentException("Visual and visibility inputs must share the exact native snapshot");
            if(workingBytes<0||workingBytes>512L*1024*1024||visuals.inputBytes()+visibility.bytes()>workingBytes)throw new IllegalArgumentException("Frozen render inputs exceed their combined CPU working budget");
        }
        public Request(WorldVisuals.Request visuals,WorldVisibility.Input visibility) { this(visuals,visibility,WorldScenePlan.Limits.DEFAULT,WorldMesher.Limits.DEFAULT,512L*1024*1024); }
        public long inputBytes() { return visuals.inputBytes()+visibility.bytes(); }
    }
    public record ModelRequest(WorldModelInputs.Request inputs,WorldVisibility.Input visibility,WorldScenePlan.Limits sceneLimits,WorldMesher.Limits sectionLimits,long workingBytes) {
        public ModelRequest {
            Objects.requireNonNull(inputs);Objects.requireNonNull(visibility);Objects.requireNonNull(sceneLimits);Objects.requireNonNull(sectionLimits);
            if(visibility.snapshot()!=inputs.snapshot())throw new IllegalArgumentException("Native model/visibility inputs must share the exact snapshot");
            if(workingBytes<0||workingBytes>512L*1024*1024||inputs.inputBytes()+visibility.bytes()>workingBytes)throw new IllegalArgumentException("Frozen native decisions exceed their render working budget");
        }
        public ModelRequest(WorldModelInputs.Request inputs,WorldVisibility.Input visibility) { this(inputs,visibility,WorldScenePlan.Limits.DEFAULT,WorldMesher.Limits.DEFAULT,512L*1024*1024); }
        public long inputBytes() { return inputs.inputBytes()+visibility.bytes(); }
    }
    private record Submission(Request ready,ModelRequest model) {
        WorldSnapshot snapshot() { return ready!=null?ready.visuals().snapshot():model.inputs().snapshot(); }
        WorldVisibility.Input visibility() { return ready!=null?ready.visibility():model.visibility(); }
        WorldScenePlan.Limits sceneLimits() { return ready!=null?ready.sceneLimits():model.sceneLimits(); }
        WorldMesher.Limits sectionLimits() { return ready!=null?ready.sectionLimits():model.sectionLimits(); }
        long workingBytes() { return ready!=null?ready.workingBytes():model.workingBytes(); }
        long inputBytes() { return ready!=null?ready.inputBytes():model.inputBytes(); }
        long frozenBytes() { return ready!=null?ready.visuals().inputBytes():model.inputs().inputBytes(); }
        long visualBudget() { return ready!=null?ready.visuals().limits().workingBytes():model.inputs().limits().visuals().workingBytes(); }
        long extraSceneBytes() { return ready!=null?ready.visuals().inputBytes()-snapshot().indexBytes()-(long)snapshot().cellCount()*4-ready.visuals().offsets().bytes()-ready.visuals().tints().bytes():model.inputs().extraSceneBytes(); }
    }
    public enum Phase { IDLE, QUEUED, INSPECTING, VISUALS, PLANNING, BUILDING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<WorldSnapshot.Source> source,Optional<UUID> snapshotId,List<WorldProblem> problems,List<ModelPlan.Problem> assetProblems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(source);Objects.requireNonNull(snapshotId);problems=List.copyOf(problems);assetProblems=List.copyOf(assetProblems); }
    }
    /** Owns the scene tickets until closed or explicitly transferred to the GPU upload path. */
    public record Completion(long revision,WorldScene.Result owner,Optional<WorldTint> tint) implements AutoCloseable {
        public Completion { Objects.requireNonNull(owner);Objects.requireNonNull(tint);if(tint.isPresent()&&(tint.orElseThrow().requiresPlaceholder()||tint.orElseThrow().scene()!=owner.scene().orElseThrow()))throw new IllegalArgumentException("Completed tint belongs to another scene"); }
        public Completion(long revision,WorldScene.Result owner) { this(revision,owner,Optional.empty()); }
        @Override public void close() { owner.close(); }
    }
    private static final class Job {
        final long revision;final Submission request;long admissionBytes;final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,Submission request) { this.revision=revision;this.request=request;admissionBytes=request.workingBytes(); }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final WorldSectionCache cache;private final RenderMemory.Account admission;private final RenderMemory memory;private final ExecutorService executor;private final long workingBytes;
    private long revision;private boolean closed,scheduled,disposed;private Job active,pending;private WorldScene.Result completed;private WorldTint completedTint;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),Optional.empty(),List.of(),List.of());
    public WorldRenderWorker() { this(1024L*1024*1024); }
    /** Aggregate admission includes active/cache CPU payload and one frozen pending input. */
    public WorldRenderWorker(long workingBytes) { this(workingBytes,null); }
    public WorldRenderWorker(long workingBytes,RenderMemory memory) { this(workingBytes,memory,WorldRenderWorker::executor); }
    WorldRenderWorker(long workingBytes,RenderMemory memory,Supplier<ExecutorService> executors) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Render worker working limit exceeds 1 GiB");this.workingBytes=workingBytes;this.memory=memory;
        admission=memory==null?null:memory.open("World preparation and scene cache");
        try {
            cache=new WorldSectionCache(WorldSectionCache.Limits.DEFAULT,admission);
            executor=Objects.requireNonNull(Objects.requireNonNull(executors).get());
        }catch(RuntimeException|Error failure) { if(admission!=null)admission.close();throw failure; }
    }
    private static ExecutorService executor() { return Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-world-render");thread.setDaemon(true);return thread;}); }
    private void reserve(long bytes) { if(admission!=null)admission.reserve(bytes); }
    /** Retained cache payload is covered by the current job ceiling rather than charged a second time. */
    private void refreshAdmission() {
        long activeBytes=active==null?0:active.admissionBytes,pendingBytes=pending==null?0:pending.request.inputBytes();
        if(active==null&&pending!=null)activeBytes=cache.stats().knownPayloadBytes();
        reserve(Math.max(activeBytes+pendingBytes,pending==null?0:pending.request.workingBytes()));
    }
    public Optional<RenderMemory.Owner> memoryUsage() { synchronized(lock) { return admission==null?Optional.empty():Optional.of(admission.stats()); } }
    public long submit(Request request) { return submit(new Submission(Objects.requireNonNull(request),null)); }
    public long submit(ModelRequest request) { return submit(new Submission(null,Objects.requireNonNull(request))); }
    /** Consumes detached native input on every outcome; source admission survives destination preflight. */
    public long submit(RenderInput<Request> input) {
        Objects.requireNonNull(input);
        try(var source=input.retain()) { requireCoordinator(source.admission());return submit(source.value()); }
        finally { input.close(); }
    }
    public long submitModel(RenderInput<ModelRequest> input) {
        Objects.requireNonNull(input);
        try(var source=input.retain()) { requireCoordinator(source.admission());return submit(source.value()); }
        finally { input.close(); }
    }
    private void requireCoordinator(RenderMemory.Hold source) {
        if(source.tracked()&&(admission==null||!source.belongsTo(memory)))throw new IllegalArgumentException("Tracked native input requires the same managed world coordinator");
    }
    private long submit(Submission request) {
        Objects.requireNonNull(request);synchronized(lock) {
            requireOpen();long running=active==null?cache.stats().knownPayloadBytes():active.request.workingBytes();
            if(Math.max(request.workingBytes(),running+request.inputBytes())>workingBytes)throw new IllegalArgumentException("Active render/cache and pending input exceed the worker CPU budget");
            long next=nextRevision();var job=new Job(next,request);var queued=status(job,Phase.QUEUED,List.of(),List.of());
            // Reject shared admission before changing revisions, cancelling jobs or dropping old output.
            reserve(Math.max(request.workingBytes(),(active==null?running:active.admissionBytes)+request.inputBytes()));
            if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);dropCompleted();pending=job;revision=next;status=queued;
            if(!scheduled) {
                scheduled=true;try { executor.execute(this::drain); }
                catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;refreshAdmission();status=status(job,Phase.FAILED,List.of(problem(failure instanceof OutOfMemoryError?WorldProblem.Code.MEMORY:WorldProblem.Code.WORKER,"The CPU render worker could not be started")),List.of()); }
            }
            return next;
        }
    }
    public long invalidate() {
        synchronized(lock) { requireOpen();long next=nextRevision();if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);pending=null;dropCompleted();refreshAdmission();revision=next;status=new Status(next,Phase.IDLE,Optional.empty(),Optional.empty(),List.of(),List.of());return next; }
    }
    public Status status() { synchronized(lock) { return status; } }
    public WorldSectionCache.Stats cacheStats() { return cache.stats(); }
    public Optional<Completion> take(long expectedRevision) {
        synchronized(lock) { if(closed||revision!=expectedRevision||completed==null)return Optional.empty();var completion=Optional.of(new Completion(revision,completed,Optional.ofNullable(completedTint)));var delivered=new Status(revision,Phase.DELIVERED,status.source(),status.snapshotId(),List.of(),List.of());completed=null;completedTint=null;status=delivered;return completion; }
    }
    private void requireOpen() { if(closed)throw new IllegalStateException("Render worker is closed"); }
    private long nextRevision() { if(revision==Long.MAX_VALUE)throw new IllegalStateException("Render revision counter exhausted");return revision+1; }
    private void dropCompleted() { if(completed!=null) { completed.close();completed=null;completedTint=null; } }
    private static WorldProblem problem(WorldProblem.Code code,String reason) { return new WorldProblem(code,Optional.empty(),reason); }
    private static Status status(Job job,Phase phase,List<WorldProblem> problems,List<ModelPlan.Problem> assets) { return new Status(job.revision,phase,Optional.of(job.request.snapshot().source()),Optional.of(job.request.snapshot().id()),problems,assets); }
    private void phase(Job job,Phase phase) { synchronized(lock) { if(!closed&&revision==job.revision&&!job.cancelled.get())status=status(job,phase,List.of(),List.of()); } }
    private void drain() {
        try {
            while(true) {
                Job job;
                synchronized(lock) { if(closed||pending==null) { active=null;scheduled=false;refreshAdmission();return; }job=pending;job.admissionBytes=Math.max(job.request.workingBytes(),cache.stats().knownPayloadBytes()+job.request.inputBytes());pending=null;active=job;refreshAdmission();status=status(job,job.request.model()!=null?Phase.INSPECTING:Phase.VISUALS,List.of(),List.of()); }
                try { execute(job); }
                finally { synchronized(lock) { active=null;job=null;refreshAdmission(); } }
            }
        }catch(RuntimeException|Error failure) {
            synchronized(lock) { active=null;scheduled=false;refreshAdmission(); }throw failure;
        }finally { synchronized(lock) { if(closed)cache.close(); } }
    }
    private WorldScene.Result build(Job job) {
        var request=job.request;if(!request.visibility().problems().isEmpty())throw new WorldProblem.Failure(request.visibility().problems().getFirst());
        // New visual requests create new atlas epochs. Prior displayed/picking/GPU tickets stay pinned.
        cache.retireUnleased();long available=Math.min(request.visualBudget(),request.workingBytes()-cache.stats().knownPayloadBytes()-request.visibility().bytes());
        if(available<request.frozenBytes())throw WorldProblem.fail(WorldProblem.Code.CACHE_BUSY,"Pinned prior scenes and frozen render input leave insufficient visual working capacity");
        WorldVisuals.Request frozen;
        if(request.model()!=null) {
            var inspected=WorldModelInputs.prepare(request.model().inputs().withWorkingBytes(available),job::cancelled);
            if(inspected.requiresPlaceholder()) { if(!inspected.assetProblems().isEmpty())throw new ModelFields.Failure(inspected.assetProblems().getFirst());throw new WorldProblem.Failure(inspected.problems().getFirst()); }
            frozen=inspected.request().orElseThrow();
        }else frozen=request.ready().visuals();
        phase(job,Phase.VISUALS);var visuals=WorldVisuals.prepare(frozen.withWorkingBytes(available),job::cancelled);
        if(visuals.requiresPlaceholder()) { if(!visuals.assetProblems().isEmpty())throw new ModelFields.Failure(visuals.assetProblems().getFirst());throw new WorldProblem.Failure(visuals.problems().getFirst()); }
        var models=visuals.models().orElseThrow();var visibility=request.visibility().bind(models.layout(),job::cancelled);
        // The request still owns the catalog and raw native inputs while scene arrays are allocated.
        // Only native indices/private choices are known to be shared with the scene graph here.
        long extra=request.extraSceneBytes();
        long sceneBudget=Math.min(request.sceneLimits().workingBytes(),request.workingBytes()-extra);
        var limits=request.sceneLimits();var bounded=new WorldScenePlan.Limits(limits.sectionEdge(),limits.sections(),limits.faces(),limits.cellRecords(),limits.markers(),limits.meshBytes(),sceneBudget);
        phase(job,Phase.PLANNING);var plan=WorldScenePlan.plan(models,visibility,bounded,request.sectionLimits(),job::cancelled);
        if(plan.requiresPlaceholder())throw new WorldProblem.Failure(plan.problems().getFirst());phase(job,Phase.BUILDING);
        return WorldScene.build(plan,visuals.raster().orElseThrow(),cache,job::cancelled);
    }
    private void execute(Job job) {
        WorldScene.Result result=null;WorldTint tint=null;List<WorldProblem> problems=List.of();List<ModelPlan.Problem> assets=List.of();boolean retained=false;
        try {
            try { result=build(job);if(result.requiresPlaceholder())problems=result.problems();else if(result.scene().orElseThrow().plan().models().tints().hasColors())tint=WorldTint.fromInput(result.scene().orElseThrow()); }
            catch(CancellationException superseded) { /* No stale result or status adoption. */ }
            catch(ModelFields.Failure failure) { assets=List.of(failure.problem); }
            catch(WorldProblem.Failure failure) { problems=List.of(failure.problem); }
            catch(RuntimeException failure) { problems=List.of(problem(WorldProblem.Code.WORKER,"CPU world rendering failed: "+failure.getClass().getSimpleName())); }
            catch(OutOfMemoryError exhausted) { problems=List.of(problem(WorldProblem.Code.MEMORY,"Not enough memory to complete CPU world rendering")); }
            synchronized(lock) {
                if(!closed&&revision==job.revision&&!job.cancelled.get()) {
                    if(result!=null&&!result.requiresPlaceholder()&&problems.isEmpty()&&assets.isEmpty()) { var ready=status(job,Phase.READY,List.of(),List.of());completed=result;completedTint=tint;retained=true;status=ready; }
                    else { if(problems.isEmpty()&&assets.isEmpty())problems=List.of(problem(WorldProblem.Code.WORKER,"CPU world rendering ended without a complete scene"));status=status(job,Phase.FAILED,problems,assets); }
                }
            }
        }finally { if(!retained&&result!=null)result.close(); }
    }
    @Override public void close() {
        Throwable failure=null;
        synchronized(lock) {
            if(disposed)return;closed=true;if(active!=null)active.cancelled.set(true);if(pending!=null)pending.cancelled.set(true);pending=null;
            try { dropCompleted(); }catch(RuntimeException|Error error) { failure=error; }
            try { refreshAdmission(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        }
        try { executor.shutdownNow(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        try { cache.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        synchronized(lock) {
            try { status=new Status(revision,Phase.CLOSED,Optional.empty(),Optional.empty(),List.of(),List.of()); }
            catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }disposed=failure==null;
        }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
