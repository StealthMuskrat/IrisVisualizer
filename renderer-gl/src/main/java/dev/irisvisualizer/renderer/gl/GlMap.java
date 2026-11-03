package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.MapRaster;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Immutable completed RGBA map texture; CPU native samples remain charged through retirement. */
public final class GlMap implements AutoCloseable {
    final GlDevice device;final GlDevice.Allocation allocation;private final int width,height;
    GlMap(GlDevice device,GlDevice.Allocation allocation,int width,int height) { this.device=device;this.allocation=allocation;this.width=width;this.height=height; }
    public static GlMap upload(GlDevice device,MapRaster raster,BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);try(var upload=GlMapUpload.begin(device,raster)) { while(!upload.ready())upload.step(GlUploadBudget.MAXIMUM,cancelled);return upload.take(); }
    }
    public int width() { return width; }
    public int height() { return height; }
    /** Borrowed only while live, in the original device context. CPU row zero uses texture V=0. */
    public int textureId() { device.live(allocation);return allocation.textures[0]; }
    public MapRaster raster() { device.live(allocation);return (MapRaster)allocation.retained; }
    @Override public void close() { device.retire(allocation); }
}
