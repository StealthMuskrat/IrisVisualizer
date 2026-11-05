package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Independently owned completed image canvas, original pixels and exact display transform. */
public final class GlImageFrame implements AutoCloseable {
    public record Info(UUID id,long revision,SourceImage.Receipt source,MapView view,int width,int height) {
        public Info { Objects.requireNonNull(id);Objects.requireNonNull(source);Objects.requireNonNull(view);if(revision<1||width<1||height<1||view.width()!=source.width()||view.height()!=source.height())throw new IllegalArgumentException("Invalid image frame receipt"); }
    }
    private static final class Shared {
        final GlDevice device;final GlDevice.Allocation journal;final GlTarget target;final GlImage image;final Info info;int references=1;boolean closing;
        Shared(GlDevice device,GlDevice.Allocation journal,GlTarget target,GlImage image,Info info) { this.device=device;this.journal=journal;this.target=target;this.image=image;this.info=info; }
    }
    private final Info info;private Shared shared;
    private GlImageFrame(Shared shared) { this.shared=shared;info=shared.info; }
    private static void check(BooleanSupplier obsolete) { if(obsolete.getAsBoolean())throw new CancellationException("Image frame request is obsolete"); }
    /** Inputs are borrowed; only a successful draw/restoration publishes a fresh private target. */
    public static GlImageFrame render(GlMapRenderer renderer,long revision,GlImage source,MapView view,int width,int height,BooleanSupplier obsolete) {
        Objects.requireNonNull(renderer);Objects.requireNonNull(source);Objects.requireNonNull(view);Objects.requireNonNull(obsolete);if(revision<1)throw new IllegalArgumentException("Invalid image frame revision");
        var device=renderer.device;device.live(renderer.allocation);if(source.device!=device)throw new GlFailure(GlFailure.Code.CONTEXT,"Image frame inputs must share the renderer device");
        var raster=source.raster();if(view.width()!=source.width()||view.height()!=source.height())throw new GlFailure(GlFailure.Code.STALE,"Image frame transform differs from its source dimensions");check(obsolete);
        GlImage owner=null;GlDevice.Allocation journal=null;GlTarget target=null;
        try {
            owner=source.retain();journal=device.reserve(0,0,0,0,0,0,0,0,0,null);device.depend(journal,source.allocation);journal.retained=raster;
            check(obsolete);target=GlTarget.create(device,width,height);device.depend(journal,target.allocation);check(obsolete);renderer.render(owner,target,view);check(obsolete);
            var info=new Info(UUID.randomUUID(),revision,raster.receipt().orElseThrow(),view,width,height);return new GlImageFrame(new Shared(device,journal,target,owner,info));
        }catch(RuntimeException|Error failure) {
            if(journal!=null)try { device.retire(journal); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(target!=null)try { target.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            if(owner!=null)try { owner.close(); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure;
        }
    }
    private Shared live() { var value=shared;if(value==null||value.closing)throw new GlFailure(GlFailure.Code.STATE,"Image frame owner is closed or closing");value.device.live(value.journal);value.device.live(value.target.allocation);return value; }
    public Info info() { return info; }
    public boolean closed() { return shared==null; }
    /** Offscreen output uses OpenGL bottom-left UVs; a top-left image widget must flip texture V. */
    public int textureId() { return live().target.textureId(); }
    GlTarget readbackTarget() { return live().target; }
    GlDevice.Allocation readbackJournal() { return live().journal; }
    /** Borrowed exact source image. Keep this frame owner live through inspection. */
    public SourceImage raster() { return live().image.raster(); }
    /** Context-thread capture; independently admitted CPU data survives frame/device closure. */
    public RenderInput<SourceImage> retainRasterInput() { return live().image.retainInput(); }
    public Optional<SourceImage.Pixel> hit(double x,double y) {
        var value=live();if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>=info.width()||y<0||y>=info.height())return Optional.empty();
        return value.image.raster().hit(info.view(),x,y);
    }
    public GlImageFrame retain() { var value=live();if(value.references>=65536)throw new GlFailure(GlFailure.Code.LIMIT,"Image frame owner count exceeds its bound");var result=new GlImageFrame(value);value.references++;return result; }
    @Override public void close() {
        var value=shared;if(value==null)return;if(!value.journal.released||!value.target.allocation.released||!value.image.allocation.released)value.device.current();
        if(value.references>1) { value.references--;shared=null;return; }value.closing=true;Throwable failure=null;
        try { value.device.retire(value.journal); }catch(RuntimeException|Error error) { failure=error; }
        try { value.target.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        try { value.image.close(); }catch(RuntimeException|Error error) { if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error); }
        if((value.journal.retired||value.journal.released)&&(value.target.allocation.retired||value.target.allocation.released)&&value.image.closed()) { value.references=0;shared=null; }
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
}
