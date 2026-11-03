package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static org.lwjgl.opengl.GL33C.*;

/** Immutable GPU snapshot. Upload consumes fresh CPU leases; already-transferred leases stay intact. */
public final class GlScene implements AutoCloseable {
    record Section(WorldSnapshot.Position origin,Optional<BlockMesh.Bounds> bounds,List<AtlasMesh.DrawRange> ranges,int geometryVao,int markerVao,int markerCount) {}
    final GlDevice device;final GlDevice.Allocation allocation;final int translucentFaces;final boolean nativeLighting;List<Section> sections;
    GlScene(GlDevice device,GlDevice.Allocation allocation,List<Section> sections,int translucentFaces,boolean nativeLighting) { this.device=device;this.allocation=allocation;this.sections=List.copyOf(sections);this.translucentFaces=translucentFaces;this.nativeLighting=nativeLighting; }
    private static void cancelled(BooleanSupplier cancelled) { if(cancelled.getAsBoolean())throw new CancellationException("GPU scene upload cancelled"); }
    /** Synchronous convenience; use GlUpload for an explicit per-frame upload budget. */
    public static GlScene upload(GlRenderer renderer,WorldScene.Result cpuOwner,BooleanSupplier cancelled) {
        return upload(renderer,cpuOwner,null,cancelled);
    }
    /** Supplied native vertex colors must belong to the exact CPU scene being transferred. */
    public static GlScene upload(GlRenderer renderer,WorldScene.Result cpuOwner,WorldTint tint,BooleanSupplier cancelled) { return upload(renderer,cpuOwner,tint,null,cancelled); }
    public static GlScene upload(GlRenderer renderer,WorldScene.Result cpuOwner,WorldTint tint,WorldLighting lighting,BooleanSupplier cancelled) {
        Objects.requireNonNull(renderer);Objects.requireNonNull(cpuOwner);Objects.requireNonNull(cancelled);
        try(var upload=GlUpload.begin(renderer,cpuOwner,tint,lighting)) {
            while(!upload.ready())upload.step(GlUploadBudget.MAXIMUM,cancelled);
            return upload.take();
        }
    }
    static void floats(GlDevice device,int target,FloatBuffer source,BooleanSupplier cancelled) {
        glBufferData(target,(long)source.remaining()*4,GL_STATIC_DRAW);device.check("vertex buffer allocation");
        for(int offset=0;offset<source.remaining();) {
            cancelled(cancelled);int count=Math.min(device.limits().stagingBytes()/4,source.remaining()-offset);var stage=device.staging();stage.limit(count*4);var values=stage.asFloatBuffer();for(int i=0;i<count;i++)values.put(source.get(source.position()+offset+i));
            glBufferSubData(target,(long)offset*4,stage);device.check("bounded vertex upload");offset+=count;
        }
    }
    static void integers(GlDevice device,int target,IntBuffer source,BooleanSupplier cancelled) {
        glBufferData(target,(long)source.remaining()*4,GL_STATIC_DRAW);device.check("index buffer allocation");
        for(int offset=0;offset<source.remaining();) {
            cancelled(cancelled);int count=Math.min(device.limits().stagingBytes()/4,source.remaining()-offset);var stage=device.staging();stage.limit(count*4);var values=stage.asIntBuffer();for(int i=0;i<count;i++)values.put(source.get(source.position()+offset+i));
            glBufferSubData(target,(long)offset*4,stage);device.check("bounded index upload");offset+=count;
        }
    }
    /** Independent CPU owner for asynchronous inspection; close it or transfer it to WorldPickIndex.prepare. */
    public WorldScene.Result retainCpuScene() { device.live(allocation);return allocation.owner.retain(); }
    public boolean hasNativeLighting() { device.live(allocation);return nativeLighting; }
    public WorldScene cpuScene() { device.live(allocation);return allocation.owner.scene().orElseThrow(); }
    @Override public void close() { try { device.retire(allocation); }finally { if(allocation.retired||allocation.released)sections=List.of(); } }
}
