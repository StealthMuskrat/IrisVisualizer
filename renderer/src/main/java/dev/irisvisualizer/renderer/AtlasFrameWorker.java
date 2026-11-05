package dev.irisvisualizer.renderer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One active atlas preparation and one replaceable pending clock; never accesses a native or GL context. */
public final class AtlasFrameWorker implements AutoCloseable {
    public static final class Request {
        private final AtlasRaster expected;private final long tick;private final double partialTick;private final NavigableMap<AssetId,TextureSprite.PixelSample> samples;
        private final int changedSprites;private final long extraBytes,reservation;
        private Request(AtlasRaster expected,long tick,double partialTick,NavigableMap<AssetId,TextureSprite.PixelSample> samples,int changedSprites,long extraBytes,long reservation) {
            this.expected=expected;this.tick=tick;this.partialTick=partialTick;this.samples=Collections.unmodifiableNavigableMap(samples);this.changedSprites=changedSprites;this.extraBytes=extraBytes;this.reservation=reservation;
        }
        /** Metadata-only preflight. Pixel patching and page copies happen later on the worker. */
        public static Request capture(AtlasRaster expected,long tick,double partialTick) {
            Objects.requireNonNull(expected);if(expected.requiresPlaceholder())throw new IllegalArgumentException("Atlas animation requires a successful exact raster");
            if(tick<0||!Double.isFinite(partialTick)||partialTick<0||partialTick>=1)throw new IllegalArgumentException("Invalid atlas animation clock");
            var layout=expected.layout();var samples=new TreeMap<AssetId,TextureSprite.PixelSample>();boolean[] pages=new boolean[layout.pages().size()];long patches=0,copies=0;int changed=0;
            for(var entry:layout.sprites().entrySet()) {
                var slot=entry.getValue();var sample=slot.source().pixelSample(tick,partialTick);samples.put(entry.getKey(),sample);
                if(!Objects.equals(expected.samples().get(entry.getKey()),sample)) {
                    changed++;patches+=slot.paddedBytes();if(!pages[slot.page()]) { pages[slot.page()]=true;copies+=layout.pages().get(slot.page()).bytes(); }
                }
            }
            long extra=patches+copies,reservation=layout.usage().orElseThrow().knownPayloadBytes()+extra;
            if(patches>layout.limits().updateBytes()||reservation>layout.limits().workingBytes())throw new IllegalArgumentException("Atlas animation patch/copy preflight exceeds its working budget");
            return new Request(expected,tick,partialTick,samples,changed,extra,reservation);
        }
        public AtlasRaster expected() { return expected; }
        public long tick() { return tick; }
        public double partialTick() { return partialTick; }
        public int changedSprites() { return changedSprites; }
        public long extraCpuBytes() { return extraBytes; }
        public long reservation() { return reservation; }
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
    private long revision,completedReservation;private boolean closed,scheduled;private Job active,pending;private Completion completed;
    private Status status=new Status(0,Phase.IDLE,Optional.empty(),0,0,List.of());
    public AtlasFrameWorker() { this(1024L*1024*1024); }
    public AtlasFrameWorker(long workingBytes) { this(workingBytes,null); }
    public AtlasFrameWorker(long workingBytes,RenderMemory memory) {
        if(workingBytes<0||workingBytes>1024L*1024*1024)throw new IllegalArgumentException("Atlas worker exceeds its aggregate working bound");this.workingBytes=workingBytes;this.memory=memory;
        executor=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"iris-atlas-frames");thread.setDaemon(true);return thread;});
    }
    private long next() { if(closed)throw new IllegalStateException("Atlas worker is closed");if(revision==Long.MAX_VALUE)throw new IllegalStateException("Atlas revision counter exhausted");return revision+1; }
    private void dropCompleted() { if(completed!=null) { completed.close();completed=null; }completedReservation=0; }
    private void cancelOwned() { if(active!=null)active.cancelled.set(true);if(pending!=null) { pending.cancelled.set(true);pending.admission.close();pending=null; }dropCompleted(); }
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
            cancelOwned();pending=job;revision=next;status=queued;
            if(!scheduled) { scheduled=true;try { executor.execute(this::drain); }catch(RuntimeException|OutOfMemoryError failure) { scheduled=false;pending=null;job.admission.close();status=failed(job,failure); } }
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
                        try { result=new Completion(job.revision,plan,hold); }catch(RuntimeException|OutOfMemoryError failure) { hold.close();throw failure; }
                        job.retainedBytes=request.reservation();completed=result;completedReservation=request.reservation();status=ready;
                    }
                }
            }
        }catch(CancellationException cancelled) { /* Obsolete clocks never publish a partial plan. */ }
        catch(RuntimeException|OutOfMemoryError failure) { synchronized(lock) { if(current(job)) { dropCompleted();status=failed(job,failure); } } }
    }
    private void drain() {
        while(true) {
            Job job;synchronized(lock) { if(closed||pending==null) { active=null;scheduled=false;return; }job=pending;pending=null;active=job; }
            try { execute(job); }finally { synchronized(lock) { active=null;var hold=job.admission;long retained=job.retainedBytes;job=null;hold.shrinkTo(retained);hold.close(); } }
        }
    }
    /** Nonblocking cancellation; active immutable inputs remain reserved until unwinding completes. */
    @Override public void close() { synchronized(lock) { if(closed)return;var done=new Status(revision,Phase.CLOSED,Optional.empty(),0,0,List.of());closed=true;cancelOwned();status=done; }executor.shutdownNow(); }
}
