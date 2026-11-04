package dev.irisvisualizer.renderer;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Immutable packed CPU arrays: position XYZ, normal XYZ, normalized sprite UV; no GL allocation. */
public final class MeshPacket {
    public static final int STRIDE_FLOATS=8,VERTICES_PER_QUAD=4,INDICES_PER_QUAD=6;
    public static final int PACKED_BYTES_PER_QUAD=VERTICES_PER_QUAD*STRIDE_FLOATS*Float.BYTES+INDICES_PER_QUAD*Integer.BYTES;
    public record Face(int firstIndex,int selection,ModelPlan.Source source,ModelPlan.Direction modelFace,
                       Optional<ModelPlan.Direction> cullface,ModelPlan.Material material,int tintIndex,int lightEmission,
                       boolean ambientOcclusion,Optional<ModelPlan.Direction> shadeDirectionOverride,Optional<NativeMeshSource> nativeSource,BlockMesh.Placement placement) {
        public Face(int firstIndex,int selection,ModelPlan.Source source,ModelPlan.Direction modelFace,Optional<ModelPlan.Direction> cullface,ModelPlan.Material material,int tintIndex,int lightEmission,boolean ambientOcclusion,Optional<ModelPlan.Direction> shadeDirectionOverride) { this(firstIndex,selection,source,modelFace,cullface,material,tintIndex,lightEmission,ambientOcclusion,shadeDirectionOverride,Optional.empty(),BlockMesh.Placement.MODEL); }
        public Face(int firstIndex,int selection,ModelPlan.Source source,ModelPlan.Direction modelFace,Optional<ModelPlan.Direction> cullface,ModelPlan.Material material,int tintIndex,int lightEmission,boolean ambientOcclusion,Optional<ModelPlan.Direction> shadeDirectionOverride,Optional<NativeMeshSource> nativeSource) { this(firstIndex,selection,source,modelFace,cullface,material,tintIndex,lightEmission,ambientOcclusion,shadeDirectionOverride,nativeSource,BlockMesh.Placement.MODEL); }
        public Face { Objects.requireNonNull(source);Objects.requireNonNull(modelFace);Objects.requireNonNull(cullface);Objects.requireNonNull(material);Objects.requireNonNull(shadeDirectionOverride);Objects.requireNonNull(nativeSource);Objects.requireNonNull(placement);if(placement==BlockMesh.Placement.CELL&&nativeSource.isEmpty())throw new IllegalArgumentException("Cell placement requires explicit native provenance");if(firstIndex<0||(nativeSource.isPresent()?selection!=-1:selection<0)||tintIndex< -1||lightEmission<0||lightEmission>15)throw new IllegalArgumentException("Invalid packed face metadata"); }
    }
    private final ClientAssetSnapshot.Receipt assets;private final RenderBlockState state;private final float[] vertices;private final int[] indices;private final List<Face> faces;
    private MeshPacket(ClientAssetSnapshot.Receipt assets,RenderBlockState state,float[] ownedVertices,int[] ownedIndices,List<Face> faces) {
        this.assets=assets;this.state=state;vertices=ownedVertices;indices=ownedIndices;this.faces=List.copyOf(faces);
    }
    static MeshPacket pack(ModelPlan plan,List<BlockMesh.Quad> quads,long maximumBytes,BooleanSupplier cancelled) {
        return pack(plan.assets(),plan.state(),quads,maximumBytes,cancelled);
    }
    static MeshPacket pack(ClientAssetSnapshot.Receipt assets,RenderBlockState state,List<BlockMesh.Quad> quads,long maximumBytes,BooleanSupplier cancelled) {
        long bytes=(long)quads.size()*PACKED_BYTES_PER_QUAD;
        if(bytes>maximumBytes)throw new ModelFields.Failure(new ModelPlan.Problem(ModelPlan.Code.LIMIT,ClientAssetSnapshot.Kind.BLOCKSTATE,state.block(),"","Packed geometry byte budget reached"));
        float[] vertices=new float[Math.multiplyExact(quads.size(),VERTICES_PER_QUAD*STRIDE_FLOATS)];int[] indices=new int[Math.multiplyExact(quads.size(),INDICES_PER_QUAD)];var faces=new ArrayList<Face>(quads.size());
        int at=0;
        for(int q=0;q<quads.size();q++) {
            if(cancelled.getAsBoolean())throw new CancellationException("Mesh packing cancelled");var quad=quads.get(q);var normal=quad.normal();
            for(var vertex:quad.vertices()) {
                vertices[at++]=vertex.x();vertices[at++]=vertex.y();vertices[at++]=vertex.z();vertices[at++]=normal.x();vertices[at++]=normal.y();vertices[at++]=normal.z();vertices[at++]=vertex.u();vertices[at++]=vertex.v();
            }
            int base=q*4,index=q*6;indices[index]=base;indices[index+1]=base+1;indices[index+2]=base+2;indices[index+3]=base;indices[index+4]=base+2;indices[index+5]=base+3;
            faces.add(new Face(index,quad.selection(),quad.source(),quad.modelFace(),quad.cullface(),quad.material(),quad.tintIndex(),quad.lightEmission(),quad.ambientOcclusion(),quad.shadeDirectionOverride(),quad.nativeSource(),quad.placement()));
        }
        if(cancelled.getAsBoolean())throw new CancellationException("Mesh packing cancelled");return new MeshPacket(assets,state,vertices,indices,faces);
    }
    public ClientAssetSnapshot.Receipt assets() { return assets; }
    public RenderBlockState state() { return state; }
    public int vertexCount() { return vertices.length/STRIDE_FLOATS; }
    public int indexCount() { return indices.length; }
    public long packedBytes() { return (long)vertices.length*Float.BYTES+(long)indices.length*Integer.BYTES; }
    /** Independent cursors, read-only heap views. GPU staging/direct-memory budgeting remains separate. */
    public FloatBuffer vertexBuffer() { return FloatBuffer.wrap(vertices).asReadOnlyBuffer(); }
    public IntBuffer indexBuffer() { return IntBuffer.wrap(indices).asReadOnlyBuffer(); }
    public float[] copyVertices() { return vertices.clone(); }
    public int[] copyIndices() { return indices.clone(); }
    public List<Face> faces() { return faces; }
    public void requireAssets(ClientAssetSnapshot snapshot) {
        Objects.requireNonNull(snapshot);var current=snapshot.receipt();
        if(!assets.archiveSha256().equals(current.archiveSha256()) || !assets.version().equals(current.version()) || !assets.expectedVersion().equals(current.expectedVersion()))
            throw new IllegalArgumentException("Mesh and texture assets belong to different captured client archives");
    }
}
