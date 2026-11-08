package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.WorldDrawOrder;
import java.util.Objects;

/** Device-charged immutable CPU face order, pinned to one GPU scene and camera pose. */
public final class GlDrawPlan implements AutoCloseable {
    final GlDevice device;private final GlDevice.Allocation allocation;private final GlScene scene;
    private GlDrawPlan(GlScene scene,GlDevice.Allocation allocation) { this.scene=scene;device=scene.device;this.allocation=allocation; }
    public static GlDrawPlan create(GlScene scene,WorldDrawOrder order) {
        validate(scene,order);try(var prepared=prepare(scene)) { return prepared.finish(order); }
    }
    private static void validate(GlScene scene,WorldDrawOrder order) {
        Objects.requireNonNull(scene);Objects.requireNonNull(order);var device=scene.device;scene.live();
        if(order.requiresPlaceholder())throw new GlFailure(GlFailure.Code.MATERIAL,"A complete translucent face order is required");
        if(order.scene()!=scene.cpuScene()||order.size()!=scene.translucentFaces)throw new GlFailure(GlFailure.Code.STALE,"Face order belongs to a different CPU scene");
    }
    /** Complete destination order bytes/journal, admitted before asynchronous source sorting. */
    static final class Prepared implements AutoCloseable {
        private final GlScene scene;private GlDevice.Allocation allocation;
        private Prepared(GlScene scene,GlDevice.Allocation allocation) { this.scene=scene;this.allocation=allocation; }
        GlDrawPlan finish(WorldDrawOrder order) {
            if(allocation==null)throw new GlFailure(GlFailure.Code.STATE,"Order admission was already consumed");validate(scene,order);scene.device.live(allocation);if(order.bytes()!=allocation.cpu)throw new GlFailure(GlFailure.Code.STATE,"Order bytes differ from complete admission");
            var result=new GlDrawPlan(scene,allocation);allocation.retained=order;allocation=null;return result;
        }
        boolean ownsResources() { return allocation!=null; }
        @Override public void close() { if(allocation!=null)try { scene.device.retire(allocation); }finally { if(allocation.retired||allocation.released)allocation=null; } }
    }
    static Prepared prepare(GlScene scene) {
        Objects.requireNonNull(scene);scene.live();var device=scene.device;GlDevice.Allocation allocation=null;
        try {
            allocation=device.reserve(0,(long)scene.translucentFaces*Long.BYTES,0,0,0,0,0,0,0,null);device.depend(allocation,scene.allocation);return new Prepared(scene,allocation);
        }catch(RuntimeException|Error failure) { if(allocation!=null)try { device.retire(allocation); }catch(RuntimeException|Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }throw failure; }
    }
    /**
     * Independently admits this immutable order for the same device/exact CPU scene and original pose.
     * The source scene wrapper may be closed; this live plan still pins its allocation and CPU owner.
     */
    public GlDrawPlan rebind(GlScene destination) {
        Objects.requireNonNull(destination);device.live(allocation);
        if(destination.device!=device)throw new GlFailure(GlFailure.Code.STALE,"Face order rebinding requires the same device");
        // This journal pins the source allocation/CPU scene even after its public scene wrapper closes.
        // The new journal depends only on destination, and independently admits the shared order bytes.
        return create(destination,(WorldDrawOrder)allocation.retained);
    }
    GlDrawPlan rebind(Prepared destination) { device.live(allocation);if(destination.scene.device!=device)throw new GlFailure(GlFailure.Code.STALE,"Order admission requires the same device");return destination.finish((WorldDrawOrder)allocation.retained); }
    boolean matchesView(GlCamera camera) { device.live(allocation);return ((WorldDrawOrder)allocation.retained).matches(camera.drawView()); }
    WorldDrawOrder order(GlScene expected,GlCamera camera) {
        device.live(allocation);scene.live();
        if(scene!=expected)throw new GlFailure(GlFailure.Code.STALE,"Face order belongs to a different GPU scene");
        var order=(WorldDrawOrder)allocation.retained;
        if(!order.matches(camera.drawView()))throw new GlFailure(GlFailure.Code.STALE,"Translucent face order does not match the current camera direction/relative range");return order;
    }
    boolean closedOrReleased() { return allocation.retired||allocation.released; }
    @Override public void close() { device.retire(allocation); }
}
