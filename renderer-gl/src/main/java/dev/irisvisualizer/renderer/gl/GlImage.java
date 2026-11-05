package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Immutable completed original RGBA image texture; CPU source pixels remain charged through retirement. */
public final class GlImage implements AutoCloseable {
    private static final class Shared {
        final GlDevice device;final GlDevice.Allocation allocation;final int width,height;int references=1;boolean closing;
        Shared(GlDevice device,GlDevice.Allocation allocation,int width,int height) { this.device=device;this.allocation=allocation;this.width=width;this.height=height; }
    }
    final GlDevice device;final GlDevice.Allocation allocation;private final int width,height;private Shared shared;
    GlImage(GlDevice device,GlDevice.Allocation allocation,int width,int height) { this(new Shared(device,allocation,width,height)); }
    private GlImage(Shared shared) { this.shared=shared;device=shared.device;allocation=shared.allocation;width=shared.width;height=shared.height; }
    public static GlImage upload(GlDevice device,SourceImage raster,BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);try(var upload=GlImageUpload.begin(device,raster)) { while(!upload.ready())upload.step(GlUploadBudget.MAXIMUM,cancelled);return upload.take(); }
    }
    public int width() { return width; }
    public int height() { return height; }
    /** Borrowed only while live, in the original device context. CPU row zero uses texture V=0. */
    public int textureId() { live();return allocation.textures[0]; }
    public SourceImage raster() { live();return (SourceImage)allocation.retained; }
    /** Context-thread admission of independent CPU data, without copying pixels or retaining a GL owner. */
    public RenderInput<SourceImage> retainInput() {
        var source=raster();var memory=device.memory().orElse(null);
        try { return RenderInput.own(source,memory==null?RenderMemory.Hold.none():memory.hold("Retained image raster",source.payloadBytes())); }
        catch(RenderMemory.LimitException failure) { throw new GlFailure(GlFailure.Code.LIMIT,failure.getMessage()); }
        catch(IllegalStateException failure) { throw new GlFailure(GlFailure.Code.STATE,failure.getMessage()); }
    }
    private Shared live() { var value=shared;if(value==null||value.closing)throw new GlFailure(GlFailure.Code.STATE,"Image owner is closed or closing");device.live(allocation);return value; }
    public boolean closed() { return shared==null; }
    /** Independent owner of the same immutable texture/source pixels; no additional payload allocation. */
    public GlImage retain() { var value=live();if(value.references>=65536)throw new GlFailure(GlFailure.Code.LIMIT,"Image owner count exceeds its bound");var result=new GlImage(value);value.references++;return result; }
    @Override public void close() {
        var value=shared;if(value==null)return;if(!allocation.released)device.current();
        if(value.references>1) { value.references--;shared=null;return; }value.closing=true;
        try { device.retire(allocation); }finally { if(allocation.retired||allocation.released) { value.references=0;shared=null; } }
    }
}
