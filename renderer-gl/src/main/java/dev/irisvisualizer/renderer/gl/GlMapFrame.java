package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Independently owned completed map canvas, original samples and exact display transform. */
public final class GlMapFrame implements AutoCloseable {
    public record Info(UUID id,long revision,MapSamples.Context context,MapSamples.Kind kind,MapSamples.Grid grid,MapView view,int width,int height) {
        public Info { Objects.requireNonNull(id);Objects.requireNonNull(context);Objects.requireNonNull(kind);Objects.requireNonNull(grid);Objects.requireNonNull(view);if(revision<1||width<1||height<1||view.width()!=grid.width()||view.height()!=grid.height())throw new IllegalArgumentException("Invalid map frame receipt"); }
    }
    private static final class Shared {
        final GlDevice device;final GlDevice.Allocation journal;final GlTarget target;final GlMap map;final Info info;int references=1;boolean closing;
        Shared(GlDevice device,GlDevice.Allocation journal,GlTarget target,GlMap map,Info info) { this.device=device;this.journal=journal;this.target=target;this.map=map;this.info=info; }
    }
    private final Info info;private Shared shared;
    private GlMapFrame(Shared shared) { this.shared=shared;info=shared.info; }
    private static void check(BooleanSupplier obsolete) { if(obsolete.getAsBoolean())throw new CancellationException("Map frame request is obsolete"); }
    /** Inputs are borrowed; only a successful draw/restoration publishes a fresh private target. */
    public static GlMapFrame render(GlMapRenderer renderer,long revision,GlMap source,MapView view,int width,int height,BooleanSupplier obsolete) {
        Objects.requireNonNull(renderer);Objects.requireNonNull(source);Objects.requireNonNull(view);Objects.requireNonNull(obsolete);if(revision<1)throw new IllegalArgumentException("Invalid map frame revision");
        var device=renderer.device;device.live(renderer.allocation);if(source.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Map frame inputs must share the renderer device");
        var raster=source.raster();if(view.width()!=source.width()||view.height()!=source.height())throw new GlFailure(GlFailure.Code.STALE,"Map frame transform differs from its source dimensions");check(obsolete);
        GlMap owner=null;GlDevice.Allocation journal=null;GlTarget target=null;
        try {
            owner=source.retain();journal=device.reserve(0,0,0,0,0,0,0,0,0,null);device.depend(journal,source.allocation);journal.retained=raster;
            check(obsolete);target=GlTarget.create(device,width,height);device.depend(journal,target.allocation);check(obsolete);renderer.render(owner,target,view);check(obsolete);
            var info=new Info(UUID.randomUUID(),revision,raster.samples().context(),raster.samples().kind(),raster.samples().grid(),view,width,height);return new GlMapFrame(new Shared(device,journal,target,owner,info));
        }catch(RuntimeException|Error failure) {
            if(journal!=null)try { device.retire(journal); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(target!=null)try { target.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(owner!=null)try { owner.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure;
        }
    }
    private Shared live() { var value=shared;if(value==null||value.closing)throw new GlFailure(GlFailure.Code.STATE,"Map frame owner is closed or closing");value.device.live(value.journal);value.device.live(value.target.allocation);return value; }
    public Info info() { return info; }
    public boolean closed() { return shared==null; }
    /** Offscreen output uses OpenGL bottom-left UVs; a top-left image widget must flip texture V. */
    public int textureId() { return live().target.textureId(); }
    GlTarget readbackTarget() { return live().target; }
    GlDevice.Allocation readbackJournal() { return live().journal; }
    /** Borrowed exact native raster. Keep this frame owner live through inspection. */
    public MapRaster raster() { return live().map.raster(); }
    public Optional<MapPick.Hit> hit(double x,double y) {
        var value=live();if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>=info.width()||y<0||y>=info.height())return Optional.empty();
        return MapPick.at(value.map.raster(),info.view(),x,y);
    }
    public GlMapFrame retain() { var value=live();if(value.references>=65536)throw new GlFailure(GlFailure.Code.LIMIT,"Map frame owner count exceeds its bound");var result=new GlMapFrame(value);value.references++;return result; }
    @Override public void close() {
        var value=shared;if(value==null)return;if(!value.journal.released||!value.target.allocation.released||!value.map.allocation.released)value.device.current();
        if(value.references>1) { value.references--;shared=null;return; }value.closing=true;Throwable failure=null;
        try { value.device.retire(value.journal); }catch(RuntimeException|Error error) { failure=error; }
        try { value.target.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        try { value.map.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if((value.journal.retired||value.journal.released)&&(value.target.allocation.retired||value.target.allocation.released)&&value.map.closed()) { value.references=0;shared=null; }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
