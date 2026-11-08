package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Independently owned immutable completed world image; replacement never redraws its target. */
public final class GlFrame implements AutoCloseable {
    public record Info(UUID id,long revision,WorldSnapshot.Source source,GlCamera camera,int width,int height,long atlasTick,double partialTick,GlRenderer.FrameStats stats,WorldClip clip,GlScene.LightingInfo lighting,Optional<WorldVisualReport.Receipt> visuals) {
        public Info(UUID id,long revision,WorldSnapshot.Source source,GlCamera camera,int width,int height,long atlasTick,double partialTick,GlRenderer.FrameStats stats) { this(id,revision,source,camera,width,height,atlasTick,partialTick,stats,WorldClip.NONE,GlScene.LightingInfo.SIMPLE); }
        public Info(UUID id,long revision,WorldSnapshot.Source source,GlCamera camera,int width,int height,long atlasTick,double partialTick,GlRenderer.FrameStats stats,WorldClip clip) { this(id,revision,source,camera,width,height,atlasTick,partialTick,stats,clip,GlScene.LightingInfo.SIMPLE); }
        public Info(UUID id,long revision,WorldSnapshot.Source source,GlCamera camera,int width,int height,long atlasTick,double partialTick,GlRenderer.FrameStats stats,WorldClip clip,GlScene.LightingInfo lighting) { this(id,revision,source,camera,width,height,atlasTick,partialTick,stats,clip,lighting,Optional.empty()); }
        public Info { Objects.requireNonNull(visuals);Objects.requireNonNull(lighting);Objects.requireNonNull(clip);Objects.requireNonNull(id);Objects.requireNonNull(source);Objects.requireNonNull(camera);Objects.requireNonNull(stats);if(revision<1||width<1||height<1||visuals.isPresent()&&!visuals.orElseThrow().source().equals(source))throw new IllegalArgumentException("Invalid completed frame receipt"); }
        public WorldRay ray(double x,double y) { return camera.ray(x,y,width,height); }
        public WorldPickIndex.Options pickOptions(WorldPickIndex.Options options) { return Objects.requireNonNull(options).withClip(clip); }
        public WorldPickWorker.Frame pickFrame() { return new WorldPickWorker.Frame(id,revision); }
        public boolean matches(WorldPickWorker.Completion completion) {
            Objects.requireNonNull(completion);
            return completion.frame().filter(pickFrame()::equals).isPresent()&&source.equals(completion.result().source())&&clip.equals(completion.result().clip());
        }
        /** Reports cover the complete assignment source, independently of camera/clip/atlas playback. */
        public boolean matches(WorldVisualReportWorker.Completion completion) {
            Objects.requireNonNull(completion);return !completion.closed()&&visuals.filter(completion.receipt()::equals).isPresent();
        }
    }
    private static final class Shared {
        final GlDevice device;final GlDevice.Allocation journal;final GlTarget target;final Info info;int references=1;boolean closing;
        Shared(GlDevice device,GlDevice.Allocation journal,GlTarget target,Info info) { this.device=device;this.journal=journal;this.target=target;this.info=info; }
    }
    private final Info info;private Shared shared;
    private GlFrame(Shared shared) { this.shared=shared;info=shared.info; }
    private static void current(BooleanSupplier obsolete) { if(obsolete.getAsBoolean())throw new CancellationException("Completed frame request is obsolete"); }
    /** Borrowed metadata preflight; creates no target, journal or independent source owner. */
    static AtlasRaster validate(GlRenderer renderer,GlScene scene,GlAtlasFrame atlas,GlDrawPlan order,GlCamera camera,int width,int height) {
        Objects.requireNonNull(renderer);Objects.requireNonNull(scene);Objects.requireNonNull(camera);var device=renderer.device;
        device.live(renderer.allocation);if(scene.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Frame inputs must share one renderer device");scene.live();
        if(scene.translucentFaces>0&&order==null)throw new GlFailure(GlFailure.Code.MATERIAL,"A complete camera-bound translucent order is required");
        if(order!=null) { if(order.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Frame order belongs to another device");order.order(scene,camera); }
        var raster=scene.raster();if(atlas!=null) { atlas.textures(scene);raster=atlas.raster(); }camera.matrix((double)width/height);camera.requireSceneRange(scene.cpuScene());GlTarget.validate(device,width,height);return raster;
    }
    /** Inputs are borrowed for this call. The returned frame owns a fresh target and retained CPU scene. */
    public static GlFrame render(GlRenderer renderer,long revision,GlScene scene,GlAtlasFrame atlas,GlDrawPlan order,GlCamera camera,int width,int height,BooleanSupplier obsolete) { return render(renderer,revision,scene,atlas,order,camera,width,height,WorldClip.NONE,obsolete); }
    public static GlFrame render(GlRenderer renderer,long revision,GlScene scene,GlAtlasFrame atlas,GlDrawPlan order,GlCamera camera,int width,int height,WorldClip clip,BooleanSupplier obsolete) {
        return render(renderer,revision,scene,atlas,order,camera,width,height,clip,true,obsolete);
    }
    /** Explicit differential mode; each call still owns a fresh immutable target and normal source receipts. */
    public static GlFrame render(GlRenderer renderer,long revision,GlScene scene,GlAtlasFrame atlas,GlDrawPlan order,GlCamera camera,int width,int height,WorldClip clip,boolean rangeCulling,BooleanSupplier obsolete) {
        return render(renderer,revision,scene,atlas,order,camera,width,height,clip,GlCullingMode.from(rangeCulling),obsolete);
    }
    /** Each strategy still creates an independent immutable target and exact-source receipt. */
    public static GlFrame render(GlRenderer renderer,long revision,GlScene scene,GlAtlasFrame atlas,GlDrawPlan order,GlCamera camera,int width,int height,WorldClip clip,GlCullingMode mode,BooleanSupplier obsolete) {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(clip);Objects.requireNonNull(renderer);Objects.requireNonNull(scene);Objects.requireNonNull(camera);Objects.requireNonNull(obsolete);if(revision<1||width<1||height<1)throw new IllegalArgumentException("Invalid frame revision or dimensions");
        var device=renderer.device;device.live(renderer.allocation);if(scene.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Frame inputs must share one renderer device");scene.live();current(obsolete);
        try(var prepared=prepare(renderer,revision,scene,atlas,order,camera,width,height,clip,mode)) { return prepared.render(obsolete); }
    }
    /** Internal one-shot owner; all known frame payload is admitted before storage or obsolete callbacks. */
    static final class Prepared implements AutoCloseable {
        private final GlRenderer renderer;private long revision;private final GlCamera camera;private final int width,height;private final WorldClip clip;private final GlCullingMode mode;
        private GlScene scene;private GlAtlasFrame atlas;private GlDrawPlan order;private GlDevice.Allocation journal,scratch;private GlTarget target;private boolean used,closed;
        private Prepared(GlRenderer renderer,long revision,GlScene scene,GlAtlasFrame atlas,GlDrawPlan order,GlCamera camera,int width,int height,WorldClip clip,GlCullingMode mode,GlDevice.Allocation journal,GlDevice.Allocation scratch,GlTarget target) {
            this.renderer=renderer;this.revision=revision;this.scene=scene;this.atlas=atlas;this.order=order;this.camera=camera;this.width=width;this.height=height;this.clip=clip;this.mode=mode;this.journal=journal;this.scratch=scratch;this.target=target;
        }
        GlFrame render(BooleanSupplier obsolete) {
            return render(atlas,order,obsolete);
        }
        GlFrame render(GlAtlasFrame atlas,GlDrawPlan order,BooleanSupplier obsolete) {
            Objects.requireNonNull(obsolete);if(used||closed)throw new GlFailure(GlFailure.Code.STATE,"Frame preparation is already used or closed");used=true;var raster=validate(renderer,scene,atlas,order,camera,width,height);var cpu=scene.cpuScene();long extra=extraCpu(cpu,raster);
            if(extra>journal.cpu)throw new GlFailure(GlFailure.Code.STATE,"Displayed atlas exceeds the admitted frame reservation");renderer.device.shrinkCpu(journal,extra);journal.retained=raster;
            current(obsolete);target.initialize();current(obsolete);var stats=renderer.renderAdmittedFrame(scene,atlas,order,target,camera,clip,mode,scratch);scratch=null;current(obsolete);
            var info=new Info(UUID.randomUUID(),revision,cpu.snapshot().source(),camera,width,height,raster.tick(),raster.partialTick(),stats,clip,scene.lightingInfo(),Optional.of(WorldVisualReport.receipt(cpu)));var result=new GlFrame(new Shared(renderer.device,journal,target,info));journal=null;target=null;return result;
        }
        void retarget(long next) { renderer.device.current();if(used||closed||next<revision)throw new GlFailure(GlFailure.Code.STATE,"Invalid prepared frame revision");revision=next; }
        void require(GlRenderer expected,long next,GlFrameQueue.Request request) {
            renderer.device.current();if(used||closed||renderer!=expected||revision!=next||scene!=request.scene()||!camera.equals(request.camera())||width!=request.width()||height!=request.height()||!clip.equals(request.clip())||mode!=GlCullingMode.HIERARCHY)throw new GlFailure(GlFailure.Code.STALE,"Frame admission differs from requested world/view");
            var raster=validate(renderer,scene,request.atlas().orElse(null),request.order().orElse(null),camera,width,height);if(extraCpu(scene.cpuScene(),raster)>journal.cpu)throw new GlFailure(GlFailure.Code.STATE,"Atlas differs from complete frame admission");
        }
        boolean ownsResources() { return journal!=null||scratch!=null||target!=null; }
        @Override public void close() {
            closed=true;var device=renderer.device;Throwable failure=null;
            if(journal!=null)try { device.retire(journal);journal=null; }catch(RuntimeException|Error error) { if(journal.retired||journal.released)journal=null;failure=error; }
            if(scratch!=null)try { device.retire(scratch);scratch=null; }catch(RuntimeException|Error error) { if(scratch.retired||scratch.released)scratch=null;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
            if(target!=null)try { target.close();target=null; }catch(RuntimeException|Error error) { if(target.allocation.retired||target.allocation.released)target=null;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
            scene=null;atlas=null;order=null;if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
        }
    }
    static Prepared prepare(GlRenderer renderer,long revision,GlScene scene,GlAtlasFrame atlas,GlDrawPlan order,GlCamera camera,int width,int height,WorldClip clip,GlCullingMode mode) {
        Objects.requireNonNull(clip);Objects.requireNonNull(mode);if(revision<1)throw new IllegalArgumentException("Invalid frame revision");var raster=validate(renderer,scene,atlas,order,camera,width,height);var cpu=scene.cpuScene();var device=renderer.device;
        return admit(renderer,revision,scene,atlas,order,camera,width,height,clip,mode,extraCpu(cpu,raster),raster);
    }
    private static long extraCpu(WorldScene cpu,AtlasRaster raster) { long bytes=0;for(int page=0;page<raster.pages().size();page++)if(raster.pages().get(page)!=cpu.raster().pages().get(page))bytes+=raster.pages().get(page).bytes();return bytes; }
    /** Admit before order preparation or atlas adoption; every layout page can be replaced independently. */
    static Prepared reserve(GlRenderer renderer,long revision,GlScene scene,GlCamera camera,int width,int height,WorldClip clip) {
        Objects.requireNonNull(renderer);Objects.requireNonNull(scene);Objects.requireNonNull(camera);Objects.requireNonNull(clip);if(revision<1)throw new IllegalArgumentException("Invalid frame revision");renderer.device.live(renderer.allocation);
        if(scene.device!=renderer.device)throw new GlFailure(GlFailure.Code.CONTEXT,"Frame reservation requires the renderer device");scene.live();camera.matrix((double)width/height);camera.requireSceneRange(scene.cpuScene());GlTarget.validate(renderer.device,width,height);
        return admit(renderer,revision,scene,null,null,camera,width,height,clip,GlCullingMode.HIERARCHY,scene.raster().rgbaBytes(),null);
    }
    private static Prepared admit(GlRenderer renderer,long revision,GlScene scene,GlAtlasFrame atlas,GlDrawPlan order,GlCamera camera,int width,int height,WorldClip clip,GlCullingMode mode,long extraCpu,AtlasRaster raster) {
        var cpu=scene.cpuScene();var device=renderer.device;
        WorldScene.Result owner=null;GlDevice.Allocation journal=null,scratch=null;GlTarget target=null;
        try {
            owner=scene.retainCpuScene();journal=device.reserve(0,extraCpu,0,0,0,0,0,0,0,owner);owner=null;journal.retained=raster;device.depend(journal,scene.allocation);
            scratch=device.reserve(0,GlWorldCulling.workingBytes(cpu,mode),0,0,0,0,0,0,0,null);target=GlTarget.admit(device,width,height);device.depend(journal,target.allocation);
            return new Prepared(renderer,revision,scene,atlas,order,camera,width,height,clip,mode,journal,scratch,target);
        }catch(RuntimeException|Error failure) {
            if(journal!=null)try { device.retire(journal); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(scratch!=null)try { device.retire(scratch); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(target!=null)try { target.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(owner!=null)try { owner.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure;
        }
    }
    private Shared live() { var value=shared;if(value==null||value.closing)throw new GlFailure(GlFailure.Code.STATE,"Completed frame owner is closed or closing");value.device.live(value.journal);value.device.live(value.target.allocation);return value; }
    public Info info() { return info; }
    public boolean closed() { return shared==null; }
    public int textureId() { return live().target.textureId(); }
    GlTarget readbackTarget() { return live().target; }
    GlDevice.Allocation readbackJournal() { return live().journal; }
    /** Borrowed exact atlas snapshot for picking; keep a retained frame owner through the query. */
    public AtlasRaster raster() { return (AtlasRaster)live().journal.retained; }
    public WorldScene.Result retainCpuScene() { return live().journal.owner.retain(); }
    public long submitPickIndex(WorldPickIndexWorker worker) { return submitPickIndex(worker,WorldPickIndex.Limits.DEFAULT); }
    /** Context-thread handoff only; hierarchy/vertex walks run on the worker without GL calls. */
    public long submitPickIndex(WorldPickIndexWorker worker,WorldPickIndex.Limits limits) {
        Objects.requireNonNull(worker);Objects.requireNonNull(limits);var value=live();
        if(value.device.memory().orElse(null)!=worker.memory().orElse(null))throw new IllegalArgumentException("World frame and index worker must share their memory coordinator");
        return worker.submit(value.journal.owner.retain(),limits);
    }
    public long submitVisualReport(WorldVisualReportWorker worker) { return submitVisualReport(worker,WorldVisualReport.Limits.DEFAULT); }
    /** Metadata-only context handoff; census, diagnostics and sorting run without GL on the worker. */
    public long submitVisualReport(WorldVisualReportWorker worker,WorldVisualReport.Limits limits) {
        Objects.requireNonNull(worker);Objects.requireNonNull(limits);var value=live();
        if(value.device.memory().orElse(null)!=worker.memory().orElse(null))throw new IllegalArgumentException("World frame and report worker must share their memory coordinator");
        return worker.submit(value.journal.owner.retain(),limits);
    }
    /** The caller keeps the index live; accepted CPU queries independently own their displayed-raster charge. */
    public long submitPick(WorldPickWorker worker,WorldPickIndex index,double x,double y,WorldPickIndex.Options options) {
        Objects.requireNonNull(worker);Objects.requireNonNull(index);Objects.requireNonNull(options);var value=live();var memory=value.device.memory().orElse(null);
        if(memory!=worker.memory().orElse(null)||memory!=index.memory().orElse(null))throw new IllegalArgumentException("World frame, index and pointer worker must share their memory coordinator");
        if(!index.matches(value.journal.owner.scene().orElseThrow()))throw new IllegalArgumentException("Picking index belongs to another exact CPU scene");
        return worker.submit(new WorldPickWorker.Request(index,info.ray(x,y),info.pickOptions(options),Optional.of((AtlasRaster)value.journal.retained),Optional.of(info.pickFrame())));
    }
    /** Independent image/picking owner, without another target or CPU/GPU payload reservation. */
    public GlFrame retain() { var value=live();if(value.references>=65536)throw new GlFailure(GlFailure.Code.LIMIT,"Completed frame owner limit reached");var result=new GlFrame(value);value.references++;return result; }
    @Override public void close() {
        var value=shared;if(value==null)return;if(!value.journal.released||!value.target.allocation.released)value.device.current();
        if(value.references>1) { value.references--;shared=null;return; }value.closing=true;Throwable failure=null;
        try { value.device.retire(value.journal); }catch(RuntimeException|Error error) { failure=error; }
        try { value.target.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if((value.journal.retired||value.journal.released)&&(value.target.allocation.retired||value.target.allocation.released)) { value.references=0;shared=null; }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
