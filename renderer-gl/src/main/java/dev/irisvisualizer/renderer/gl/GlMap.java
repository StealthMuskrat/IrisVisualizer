package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.MapRaster;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Immutable completed RGBA map texture; CPU native samples remain charged through retirement. */
public final class GlMap implements AutoCloseable {
    private static final class Shared {
        final GlDevice device;final GlDevice.Allocation allocation;final int width,height;int references=1;boolean closing;
        Shared(GlDevice device,GlDevice.Allocation allocation,int width,int height) { this.device=device;this.allocation=allocation;this.width=width;this.height=height; }
    }
    final GlDevice device;final GlDevice.Allocation allocation;private final int width,height;private Shared shared;
    GlMap(GlDevice device,GlDevice.Allocation allocation,int width,int height) { this(new Shared(device,allocation,width,height)); }
    private GlMap(Shared shared) { this.shared=shared;device=shared.device;allocation=shared.allocation;width=shared.width;height=shared.height; }
    public static GlMap upload(GlDevice device,MapRaster raster,BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);try(var upload=GlMapUpload.begin(device,raster)) { while(!upload.ready())upload.step(GlUploadBudget.MAXIMUM,cancelled);return upload.take(); }
    }
    public int width() { return width; }
    public int height() { return height; }
    /** Borrowed only while live, in the original device context. CPU row zero uses texture V=0. */
    public int textureId() { live();return allocation.textures[0]; }
    public MapRaster raster() { live();return (MapRaster)allocation.retained; }
    private Shared live() { var value=shared;if(value==null||value.closing)throw new GlFailure(GlFailure.Code.STATE,"Map owner is closed or closing");device.live(allocation);return value; }
    public boolean closed() { return shared==null; }
    /** Independent owner of the same immutable texture/native samples; no additional payload allocation. */
    public GlMap retain() { var value=live();if(value.references>=65536)throw new GlFailure(GlFailure.Code.LIMIT,"Map owner count exceeds its bound");var result=new GlMap(value);value.references++;return result; }
    @Override public void close() {
        var value=shared;if(value==null)return;if(!allocation.released)device.current();
        if(value.references>1) { value.references--;shared=null;return; }value.closing=true;
        try { device.retire(allocation); }finally { if(allocation.retired||allocation.released) { value.references=0;shared=null; } }
    }
}
