package dev.irisvisualizer.renderer;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

/** Immutable mesh packet with atlas UVs. Original geometry and source materials stay available. */
public final class AtlasMesh {
    public record Face(MeshPacket.Face source,int page,TexturePreparation.AlphaClass alpha) {
        public Face { Objects.requireNonNull(source);Objects.requireNonNull(alpha);if(page<0)throw new IllegalArgumentException("Invalid atlas face page"); }
    }
    /** Consecutive original faces only. These are CPU candidates, not an executed transparency policy. */
    public record DrawRange(int firstIndex,int indexCount,int page,TexturePreparation.AlphaClass alpha) {
        public DrawRange { Objects.requireNonNull(alpha);if(firstIndex<0||firstIndex%6!=0||indexCount<6||indexCount%6!=0||page<0)throw new IllegalArgumentException("Invalid atlas draw range"); }
    }
    private final AtlasLayout layout;private final TexturePreparation preparation;private final float[] vertices;private final int[] indices;
    private final List<Face> faces;private final List<DrawRange> ranges;private final List<ModelPlan.Problem> problems;
    private AtlasMesh(AtlasLayout layout,TexturePreparation preparation,float[] ownedVertices,int[] ownedIndices,List<Face> faces,List<DrawRange> ranges,List<ModelPlan.Problem> problems) {
        this.layout=Objects.requireNonNull(layout);this.preparation=Objects.requireNonNull(preparation);vertices=ownedVertices;indices=ownedIndices;this.faces=List.copyOf(faces);this.ranges=List.copyOf(ranges);this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(vertices.length!=0||indices.length!=0||!faces.isEmpty()||!ranges.isEmpty()))throw new IllegalArgumentException("Partial bound mesh escaped failure");
    }
    static AtlasMesh success(AtlasLayout layout,TexturePreparation input,float[] vertices,int[] indices,List<Face> faces,List<DrawRange> ranges) { return new AtlasMesh(layout,input,vertices,indices,faces,ranges,List.of()); }
    static AtlasMesh placeholder(AtlasLayout layout,TexturePreparation input,List<ModelPlan.Problem> problems) { if(problems.isEmpty())throw new IllegalArgumentException("Placeholder requires a problem");return new AtlasMesh(layout,input,new float[0],new int[0],List.of(),List.of(),problems); }
    public AtlasLayout layout() { return layout; }
    public TexturePreparation preparation() { return preparation; }
    public ClientAssetSnapshot.Receipt assets() { return preparation.assets(); }
    public RenderBlockState state() { return preparation.state(); }
    public int vertexCount() { return vertices.length/MeshPacket.STRIDE_FLOATS; }
    public int indexCount() { return indices.length; }
    public long packedBytes() { return (long)vertices.length*Float.BYTES+(long)indices.length*Integer.BYTES; }
    public FloatBuffer vertexBuffer() { return FloatBuffer.wrap(vertices).asReadOnlyBuffer(); }
    public IntBuffer indexBuffer() { return IntBuffer.wrap(indices).asReadOnlyBuffer(); }
    public float[] copyVertices() { return vertices.clone(); }
    public int[] copyIndices() { return indices.clone(); }
    public List<Face> faces() { return faces; }
    public List<DrawRange> ranges() { return ranges; }
    public List<ModelPlan.Problem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    public void requireRaster(AtlasRaster raster) {
        Objects.requireNonNull(raster);if(requiresPlaceholder()||raster.requiresPlaceholder()||raster.layout()!=layout)throw new IllegalArgumentException("Mesh and raster must share the exact successful atlas layout epoch");
    }
}
