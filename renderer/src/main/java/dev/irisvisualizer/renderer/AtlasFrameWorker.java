package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One active atlas preparation and one replaceable pending clock; never accesses a native or GL context. */
public final class AtlasFrameWorker implements AutoCloseable {
    public static final class Request {
        private final AtlasRaster expected;private final long tick;private final double partialTick;private final NavigableMap<AssetId,TextureSprite.PixelSample> samples;
        private final int changedSprites;private final long extraBytes,reservation,lastTick;
        private Request(AtlasRaster expected,long tick,double partialTick,NavigableMap<AssetId,TextureSprite.PixelSample> samples,int changedSprites,long extraBytes,long reservation,long lastTick) {
            this.expected=expected;this.tick=tick;this.partialTick=partialTick;this.samples=Collections.unmodifiableNavigableMap(samples);this.changedSprites=changedSprites;this.extraBytes=extraBytes;this.reservation=reservation;this.lastTick=lastTick;
        }
        /** Metadata-only preflight. Pixel patching and page copies happen later on the worker. */
        public static Request capture(AtlasRaster expected,long tick,double partialTick) {
            Objects.requireNonNull(expected);if(expected.requiresPlaceholder())throw new IllegalArgumentException("Atlas animation requires a successful exact raster");
            if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid atlas animation clock");
            var layout=expected.layout();NavigableMap<AssetId,TextureSprite.PixelSample> samples=layout.timeVaryingSprites().isEmpty()?Collections.emptyNavigableMap():new TreeMap<>();boolean[] pages=null;long patches=0,copies=0,lastTick=Long.MAX_VALUE;int changed=0;
            for(var entry:layout.timeVaryingSprites().entrySet()) {
                var slot=entry.getValue();var window=slot.source().pixelWindow(tick,partialTick);var sample=window.sample();samples.put(entry.getKey(),sample);lastTick=Math.min(lastTick,window.lastTick());
                if(!Objects.equals(expected.samples().get(entry.getKey()),sample)) {
                    changed++;patches+=slot.paddedBytes();if(pages==null)pages=new boolean[layout.pages().size()];if(!pages[slot.page()]) { pages[slot.page()]=true;copies+=layout.pages().get(slot.page()).bytes(); }
                }
            }
            long extra=patches+copies,reservation=layout.usage().orElseThrow().knownPayloadBytes()+extra;
            if(patches>layout.limits().updateBytes()||reservation>layout.limits().workingBytes())throw new IllegalArgumentException("Atlas animation patch/copy preflight exceeds its working budget");
            return new Request(expected,tick,partialTick,samples,changed,extra,reservation,lastTick);
        }
        public AtlasRaster expected() { return expected; }
        public long tick() { return tick; }
        public double partialTick() { return partialTick; }
        public int changedSprites() { return changedSprites; }
        public long extraCpuBytes() { return extraBytes; }
        public long reservation() { return reservation; }
        /** Inclusive forward interval for the same canonical samples; static atlases also admit backward clocks. */
        public long lastTick() { return lastTick; }
        public boolean matchesClock(long tick,double partialTick) {
            if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid atlas animation clock");
            return samples.isEmpty()||tick>=this.tick&&tick<=lastTick;
        }
        /** Equal canonical sampling inputs, including blend progress and repeated schedule entries. */
        public boolean samePixels(Request other) { return other!=null&&expected==other.expected&&samples.equals(other.samples); }
    }
    public enum Phase { IDLE, WAITING, QUEUED, PATCHING, COPYING, READY, DELIVERED, FAILED, CLOSED }
    public record Status(long revision,Phase phase,Optional<ClientAssetSnapshot.Receipt> assets,long tick,double partialTick,List<ModelPlan.Problem> problems) {
        public Status { Objects.requireNonNull(phase);Objects.requireNonNull(assets);problems=List.copyOf(problems); }
    }
    public record Completion(long revision,AtlasFramePlan plan,RenderMemory.Hold admission) implements AutoCloseable {
        public Completion { Objects.requireNonNull(plan);Objects.requireNonNull(admission);if(revision<1||plan.requiresPlaceholder())throw new IllegalArgumentException("Invalid complete atlas plan"); }
        public Completion(long revision,AtlasFramePlan plan) { this(revision,plan,RenderMemory.Hold.none()); }
        public RenderInput<AtlasFramePlan> retainInput() { return RenderInput.own(plan,admission.retain()); }
        @Override public void close() { admission.close(); }
    }
    private static final class Job {
        final long revision;final Request request;RenderMemory.Hold admission=RenderMemory.Hold.none();long retainedBytes;final AtomicBoolean cancelled=new AtomicBoolean();
        Job(long revision,Request request) { this.revision=revision;this.request=request; }
        boolean cancelled() { return cancelled.get()||Thread.currentThread().isInterrupted(); }
    }
    private final Object lock=new Object();private final ExecutorService executor;private final long workingBytes;private final RenderMemory memory;
    private long revision,completedReservation;private boolean closed,scheduled,disposed;private Job active,pending;private Completion completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),0,0,List.of());
    public AtlasFrameWorker() { this(1024L*1024*1024); }
    public AtlasFrameWorker(long workingBytes) { this(workingBytes,null); }
    public AtlasFrameWorker(long workingBytes,RenderMemory memory) { this(workingBytes,memory,AtlasFrameWorker::executor); }
    AtlasFrameWorker(long workingBytes,RenderMemory memory,Supplier<ExecutorService> executors) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Atlas worker exceeds its aggregate working bound");this.workingBytes=workingBytes;this.memory=memory;
        executor=Objects.requireNonNull(Objects.requireNonNull(executors).get());
    }
    private static ExecutorService executor() { return Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-atlas-frames");thread.setDaemon(true);return thread;}); }
    /**
     * Checks the next-submit gate and tracked destination identity without reserving, cancelling or advancing work.
     * A null destination denotes external admission; untracked workers remain compatible with any destination.
     * This is a snapshot, not an exclusive claim: the host must keep this borrowed worker exclusive through use.
     */
    public void validateDestination(RenderMemory destination) {
        synchronized(lock) {
            next();
            if(memory!=null&&memory!=destination)throw new IllegalArgumentException("Tracked atlas worker requires the same destination coordinator");
        }
    }
    private long next() { if(closed)throw new IllegalStateException("Atlas worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Atlas revision counter exhausted");return revision+1; }
    private void dropCompleted() { var old=completed;completed=null;completedReservation=0;if(old!=null)old.close(); }
    private void cancelOwned() {
        if(active!=null)active.cancelled.set(true);Throwable failure=null;
        if(pending!=null) { var old=pending;pending=null;old.cancelled.set(true);try { old.admission.close(); }catch(RuntimeException|Error cleanup) { failure=cleanup; } }
        try { dropCompleted(); }catch(RuntimeException|Error cleanup) { if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup); }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    public long invalidate() {
        synchronized(lock) { long next=next();var waiting=new Status(next,Phase.WAITING,Optional.empty(),0,0,List.of());cancelOwned();revision=next;status=waiting;return next; }
    }
    /** Rejection preserves the prior revision, pending input and completion. Cancelled active work stays charged until it unwinds. */
    public long submit(Request request) {
        Objects.requireNonNull(request);synchronized(lock) {
            long next=next(),reserved=active==null?0:active.request.reservation();
            if(request.reservation()>workingBytes-reserved)throw new IllegalStateException("Active and pending atlas preparation reservations exceed the worker budget");
            var job=new Job(next,request);var queued=state(job,Phase.QUEUED,List.of());
            job.admission=memory==null?RenderMemory.Hold.none():memory.holdReplacing("Atlas preparation",request.reservation(),pending==null?RenderMemory.Hold.none():pending.admission,completed==null?RenderMemory.Hold.none():completed.admission());
            try { cancelOwned(); }catch(RuntimeException|Error failure) { try { job.admission.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }pending=job;revision=next;status=queued;
            if(!scheduled) { scheduled=true;try { executor.execute(this::drain); }catch(RuntimeException|Error failure) { startFailed(job,failure); } }
            return next;
        }
    }
    private static Status state(Job job,Phase phase,List<ModelPlan.Problem> problems) { return new Status(job.revision,phase,Optional.of(job.request.expected().assets()),job.request.tick(),job.request.partialTick(),problems); }
    private static Status failed(Job job,Throwable failure) {
        return state(job,Phase.FAILED,List.of(new ModelPlan.Problem(failure instanceof OutOfMemoryError?ModelPlan.Code.MEMORY:ModelPlan.Code.ATLAS,ClientAssetSnapshot.Kind.BLOCKSTATE,job.request.expected().layout().owner(),"","Atlas frame preparation failed: "+failure.getClass().getSimpleName())));
    }
    public Status status() { synchronized(lock) { return status; } }
    /** Includes obsolete work until it leaves the worker stack. Caller-transferred plans are external owners. */
    public long reservedBytes() {
        synchronized(lock) { return (active==null?0:active.request.reservation())+(pending==null?0:pending.request.reservation())+(completed==null||active!=null&&active.revision==completed.revision()?0:completedReservation); }
    }
    public Optional<Completion> take(long expectedRevision) {
        synchronized(lock) {
            if(closed||expectedRevision!=revision||completed==null)return Optional.empty();var value=Optional.of(completed);var delivered=new Status(revision,Phase.DELIVERED,status.assets(),status.tick(),status.partialTick(),List.of());completed=null;completedReservation=0;status=delivered;return value;
        }
    }
    private boolean current(Job job) { return !closed&&job.revision==revision&&!job.cancelled.get(); }
    private void phase(Job job,Phase phase) { synchronized(lock) { if(current(job))status=state(job,phase,List.of()); } }
    private void execute(Job job) {
        try {
            AtlasBuilder.check(job::cancelled);phase(job,Phase.PATCHING);var request=job.request;var update=AtlasImages.updates(request.expected(),request.tick(),request.partialTick(),job::cancelled);
            if(update.requiresPlaceholder()) { synchronized(lock) { if(current(job))status=state(job,Phase.FAILED,update.problems()); }return; }
            phase(job,Phase.COPYING);var plan=AtlasFramePlan.prepare(request.expected(),update,job::cancelled);AtlasBuilder.check(job::cancelled);
            synchronized(lock) {
                if(current(job)) {
                    if(plan.requiresPlaceholder())status=state(job,Phase.FAILED,plan.problems());
                    else {
                        if(plan.extraCpuBytes()!=request.extraCpuBytes())throw new IllegalStateException("Atlas plan payload differs from metadata preflight");
                        var ready=state(job,Phase.READY,List.of());var hold=job.admission.retain();Completion result;
                        try { result=new Completion(job.revision,plan,hold); }catch(RuntimeException|Error failure) { try { hold.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
                        job.retainedBytes=request.reservation();completed=result;completedReservation=request.reservation();status=ready;
                    }
                }
            }
        }catch(CancellationException cancelled) { /* Obsolete clocks never publish a partial plan. */ }
        catch(RuntimeException|OutOfMemoryError failure) { synchronized(lock) { if(current(job)) { dropCompleted();status=failed(job,failure); } } }
    }
    /** Caller holds the worker lock; a task that was never scheduled must not keep the dispatch gate set. */
    private void startFailed(Job job,Throwable failure) {
        scheduled=false;pending=null;boolean cleanupFailed=false;
        try { job.admission.close(); }catch(RuntimeException|Error cleanup) { cleanupFailed=true;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        try { status=failed(job,failure); }catch(RuntimeException|Error cleanup) { cleanupFailed=true;if(cleanup!=failure)failure.addSuppressed(cleanup); }
        // Preserve existing FAILED-token returns for ordinary rejection/OOM; propagate other errors after cleanup.
        if(cleanupFailed||failure instanceof Error&&!(failure instanceof OutOfMemoryError)) {
            if(failure instanceof RuntimeException runtime)throw runtime;throw (Error)failure;
        }
    }
    /** An escaping job failure may publish only for that job, never over a newer pending request. */
    private void taskFailed(Job job,Throwable failure) {
        synchronized(lock) {
            if(!closed&&job.revision==revision&&!job.cancelled.get()&&completed==null)try { status=failed(job,failure); }
            catch(RuntimeException|Error publication) { if(publication!=failure)failure.addSuppressed(publication); }
        }
    }
    /** The previous drain has unwound; keep an already accepted latest request dispatchable. */
    private void drainFailed(Throwable failure) {
        synchronized(lock) {
            if(active!=null)taskFailed(active,failure);active=null;scheduled=false;
            if(!closed&&pending!=null) {
                var next=pending;scheduled=true;
                try { executor.execute(this::drain); }
                catch(RuntimeException|Error dispatch) {
                    try { startFailed(next,dispatch); }catch(RuntimeException|Error cleanup) { if(cleanup!=dispatch)dispatch.addSuppressed(cleanup); }
                    if(dispatch!=failure)failure.addSuppressed(dispatch);
                }
            }
        }
    }
    private void drain() {
        try {
            while(true) {
                Job job;synchronized(lock) { if(closed||pending==null) { active=null;scheduled=false;return; }job=pending;pending=null;active=job; }
                Throwable failure=null;
                try { execute(job); }
                catch(RuntimeException|Error error) { failure=error;taskFailed(job,error);throw error; }
                finally {
                    synchronized(lock) {
                        active=null;var hold=job.admission;long retained=job.retainedBytes;job=null;
                        try(hold) { hold.shrinkTo(retained); }
                        catch(RuntimeException|Error cleanup) { if(failure==null)throw cleanup;if(cleanup!=failure)failure.addSuppressed(cleanup); }
                    }
                }
            }
        }catch(RuntimeException|Error failure) { drainFailed(failure);throw failure; }
    }
    /** Nonblocking cancellation; active immutable inputs remain reserved until unwinding completes. */
    @Override public void close() {
        Throwable failure=null;
        synchronized(lock) { if(disposed)return;closed=true;try { cancelOwned(); }catch(RuntimeException|Error error) { failure=error; } }
        try { executor.shutdownNow(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        synchronized(lock) {
            try { status=new Status(revision,Phase.CLOSED,Optional.empty(),0,0,List.of()); }
            catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }disposed=failure==null;
        }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
