package dev.irisvisualizer.renderer.gl;

import dev.irisvisualizer.renderer.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static org.lwjgl.opengl.GL33C.*;

/** Immutable GPU snapshot. Upload consumes fresh CPU leases; already-transferred leases stay intact. */
public final class GlScene implements AutoCloseable {
    record Section(WorldSnapshot.Position origin,Optional<BlockMesh.Bounds> bounds,List<AtlasMesh.DrawRange> ranges,int geometryVao,int markerVao,int markerCount,int vertexBuffer,int indexBuffer,int markerBuffer) {}
    public record LightingInfo(boolean supplied,String policy) {
        public static final LightingInfo SIMPLE=new LightingInfo(false,"IrisVisualizer simple directional lighting; declared shade direction and display emission floor");
        public LightingInfo { Objects.requireNonNull(policy);if(policy.isBlank()||policy.length()>256)throw new IllegalArgumentException("Invalid lighting receipt policy"); }
    }
    private static final class Shared { int references=1;boolean closing; }
    private final Shared shared;private boolean closed;
    final GlDevice device;final GlDevice.Allocation allocation;final int translucentFaces;final boolean nativeLighting;private final LightingInfo lightingInfo;List<Section> sections;
    GlScene(GlDevice device,GlDevice.Allocation allocation,List<Section> sections,int translucentFaces,boolean nativeLighting) { this(device,allocation,sections,translucentFaces,nativeLighting?new LightingInfo(true,"Supplied final RGB lighting"):LightingInfo.SIMPLE); }
    GlScene(GlDevice device,GlDevice.Allocation allocation,List<Section> sections,int translucentFaces,LightingInfo lightingInfo) { this.device=device;this.allocation=allocation;this.sections=List.copyOf(sections);this.translucentFaces=translucentFaces;this.lightingInfo=Objects.requireNonNull(lightingInfo);nativeLighting=lightingInfo.supplied();shared=new Shared(); }
    private GlScene(GlScene source) { device=source.device;allocation=source.allocation;sections=source.sections;translucentFaces=source.translucentFaces;lightingInfo=source.lightingInfo;nativeLighting=source.nativeLighting;shared=source.shared; }
    void live() { if(closed||shared.closing)throw new GlFailure(GlFailure.Code.STATE,"World scene owner is closed or closing");device.live(allocation); }
    public boolean closed() { return closed; }
    /** Internal copy-source ownership; no CPU/GPU arrays or resource names are duplicated. */
    GlScene retain() { live();if(shared.references>=65536)throw new GlFailure(GlFailure.Code.LIMIT,"World scene owner count reached");var result=new GlScene(this);shared.references++;return result; }
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
    public WorldScene.Result retainCpuScene() { live();return allocation.owner.retain(); }
    public boolean hasNativeLighting() { live();return nativeLighting; }
    public LightingInfo lightingInfo() { live();return lightingInfo; }
    public WorldScene cpuScene() { live();return allocation.owner.scene().orElseThrow(); }
    @Override public void close() {
        if(closed)return;if(!allocation.released)device.current();
        if(shared.references>1) { shared.references--;closed=true;sections=List.of();return; }shared.closing=true;
        try { device.retire(allocation); }finally { if(allocation.retired||allocation.released) { shared.references=0;closed=true;sections=List.of(); } }
    }
}
