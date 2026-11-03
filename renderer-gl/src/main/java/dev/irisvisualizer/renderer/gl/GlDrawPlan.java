package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.WorldDrawOrder;
import java.util.Objects;

/** Device-charged immutable CPU face order, pinned to one GPU scene and camera pose. */
public final class GlDrawPlan implements AutoCloseable {
    final GlDevice device;private final GlDevice.Allocation allocation;private final GlScene scene;
    private GlDrawPlan(GlScene scene,GlDevice.Allocation allocation) { this.scene=scene;device=scene.device;this.allocation=allocation; }
    public static GlDrawPlan create(GlScene scene,WorldDrawOrder order) {
        Objects.requireNonNull(scene);Objects.requireNonNull(order);var device=scene.device;device.live(scene.allocation);
        if(order.requiresPlaceholder())throw new GlFailure(GlFailure.Code.MATERIAL,"A complete translucent face order is required");
        if(order.scene()!=scene.cpuScene()||order.size()!=scene.translucentFaces)throw new GlFailure(GlFailure.Code.STALE,"Face order belongs to a different CPU scene");
        GlDevice.Allocation allocation=null;
        try {
            allocation=device.reserve(0,order.bytes(),0,0,0,0,0,0,0,null);allocation.retained=order;device.depend(allocation,scene.allocation);return new GlDrawPlan(scene,allocation);
        }catch(RuntimeException|Error failure) { if(allocation!=null)try { device.retire(allocation); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    WorldDrawOrder order(GlScene expected,GlCamera camera) {
        device.live(allocation);device.live(scene.allocation);
        if(scene!=expected)throw new GlFailure(GlFailure.Code.STALE,"Face order belongs to a different GPU scene");
        var order=(WorldDrawOrder)allocation.retained;
        if(!order.view().equals(camera.drawView()))throw new GlFailure(GlFailure.Code.STALE,"Translucent face order does not match the current camera pose");return order;
    }
    @Override public void close() { device.retire(allocation); }
}
