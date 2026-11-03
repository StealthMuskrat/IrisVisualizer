package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Independently owned immutable completed world image; replacement never redraws its target. */
public final class GlFrame implements AutoCloseable {
    public record Info(UUID id,long revision,WorldSnapshot.Source source,GlCamera camera,int width,int height,long atlasTick,double partialTick,GlRenderer.FrameStats stats,WorldClip clip,GlScene.LightingInfo lighting) {
        public Info(UUID id,long revision,WorldSnapshot.Source source,GlCamera camera,int width,int height,long atlasTick,double partialTick,GlRenderer.FrameStats stats) { this(id,revision,source,camera,width,height,atlasTick,partialTick,stats,WorldClip.NONE,GlScene.LightingInfo.SIMPLE); }
        public Info(UUID id,long revision,WorldSnapshot.Source source,GlCamera camera,int width,int height,long atlasTick,double partialTick,GlRenderer.FrameStats stats,WorldClip clip) { this(id,revision,source,camera,width,height,atlasTick,partialTick,stats,clip,GlScene.LightingInfo.SIMPLE); }
        public Info { Objects.requireNonNull(lighting);Objects.requireNonNull(clip);Objects.requireNonNull(id);Objects.requireNonNull(source);Objects.requireNonNull(camera);Objects.requireNonNull(stats);if(revision<1||width<1||height<1)throw new IllegalArgumentException("Invalid completed frame receipt"); }
        public WorldRay ray(double x,double y) { return camera.ray(x,y,width,height); }
        public WorldPickIndex.Options pickOptions(WorldPickIndex.Options options) { return Objects.requireNonNull(options).withClip(clip); }
    }
    private static final class Shared {
        final GlDevice device;final GlDevice.Allocation journal;final GlTarget target;final Info info;int references=1;boolean closing;
        Shared(GlDevice device,GlDevice.Allocation journal,GlTarget target,Info info) { this.device=device;this.journal=journal;this.target=target;this.info=info; }
    }
    private final Info info;private Shared shared;
    private GlFrame(Shared shared) { this.shared=shared;info=shared.info; }
    private static void current(BooleanSupplier obsolete) { if(obsolete.getAsBoolean())throw new CancellationException("Completed frame request is obsolete"); }
    /** Inputs are borrowed for this call. The returned frame owns a fresh target and retained CPU scene. */
    public static GlFrame render(GlRenderer renderer,long revision,GlScene scene,GlAtlasFrame atlas,GlDrawPlan order,GlCamera camera,int width,int height,BooleanSupplier obsolete) { return render(renderer,revision,scene,atlas,order,camera,width,height,WorldClip.NONE,obsolete); }
    public static GlFrame render(GlRenderer renderer,long revision,GlScene scene,GlAtlasFrame atlas,GlDrawPlan order,GlCamera camera,int width,int height,WorldClip clip,BooleanSupplier obsolete) {
        Objects.requireNonNull(clip);Objects.requireNonNull(renderer);Objects.requireNonNull(scene);Objects.requireNonNull(camera);Objects.requireNonNull(obsolete);if(revision<1||width<1||height<1)throw new IllegalArgumentException("Invalid frame revision or dimensions");
        var device=renderer.device;device.live(renderer.allocation);if(scene.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Frame inputs must share one renderer device");device.live(scene.allocation);current(obsolete);
        if(scene.translucentFaces>0&&order==null)throw new GlFailure(GlFailure.Code.MATERIAL,"A complete camera-bound translucent order is required");
        if(order!=null) { if(order.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Frame order belongs to another device");order.order(scene,camera); }
        var cpu=scene.cpuScene();AtlasRaster raster=cpu.raster();if(atlas!=null) { atlas.textures(scene);raster=atlas.raster(); }camera.matrix((double)width/height);
        long extraCpu=0;for(int page=0;page<raster.pages().size();page++)if(raster.pages().get(page)!=cpu.raster().pages().get(page))extraCpu+=raster.pages().get(page).bytes();
        WorldScene.Result owner=null;GlDevice.Allocation journal=null;GlTarget target=null;
        try {
            owner=scene.retainCpuScene();journal=device.reserve(0,extraCpu,0,0,0,0,0,0,0,owner);owner=null;journal.retained=raster;device.depend(journal,scene.allocation);
            current(obsolete);target=GlTarget.create(device,width,height);device.depend(journal,target.allocation);current(obsolete);
            var stats=renderer.renderFrame(scene,atlas,order,target,camera,clip);current(obsolete);
            var info=new Info(UUID.randomUUID(),revision,cpu.snapshot().source(),camera,width,height,raster.tick(),raster.partialTick(),stats,clip,scene.lightingInfo());return new GlFrame(new Shared(device,journal,target,info));
        }catch(RuntimeException|Error failure) {
            if(journal!=null)try { device.retire(journal); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
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
