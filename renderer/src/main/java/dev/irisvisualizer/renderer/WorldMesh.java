package dev.irisvisualizer.renderer;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

/** Immutable section-local CPU geometry, with exact integer world origin and native picking provenance. */
public final class WorldMesh {
    public static final int TRANSLUCENT_BYTES_PER_FACE=Integer.BYTES+3*Double.BYTES;
    public static final int BATCH_FACES=128;
    static long batchBytes(int faces) { return ((faces+(long)BATCH_FACES-1)/BATCH_FACES)*6*Float.BYTES; }
    public record Cell(int sourceCell,int visualEntry,int firstFace,int faceCount,int marker) {
        public Cell { if(sourceCell<0||visualEntry<0||firstFace<0||faceCount<0||marker< -1||marker>=0&&faceCount!=0)throw new IllegalArgumentException("Invalid world cell span"); }
    }
    public record Face(int cell,int prototypeFace,AtlasMesh.Face source) {
        public Face { Objects.requireNonNull(source);if(cell<0||prototypeFace<0)throw new IllegalArgumentException("Invalid world face provenance"); }
    }
    /** Unit-cell visual diagnostic for a later dedicated marker pass; never replaces generated data. */
    public record Marker(int cell,WorldSnapshot.Position position,RenderBlockState state,List<ModelPlan.Problem> problems) {
        public Marker { Objects.requireNonNull(position);Objects.requireNonNull(state);problems=List.copyOf(problems);if(cell<0||problems.isEmpty())throw new IllegalArgumentException("Invalid visual placeholder marker"); }
    }
    /** Output includes packed geometry, face-batch bounds and CPU-only translucent indices/centers. */
    public record Usage(long sourceIndexBytes,long visibilityBytes,long prototypeBytes,long atlasPayloadBytes,long outputBytes,
                        int requestedCells,int nonEmptyCells,int faces,int markers,int translucentFaces,int tintedFaces) {
        public Usage(long sourceIndexBytes,long visibilityBytes,long prototypeBytes,long atlasPayloadBytes,long outputBytes,int requestedCells,int nonEmptyCells,int faces,int markers) { this(sourceIndexBytes,visibilityBytes,prototypeBytes,atlasPayloadBytes,outputBytes,requestedCells,nonEmptyCells,faces,markers,0); }
        public Usage(long sourceIndexBytes,long visibilityBytes,long prototypeBytes,long atlasPayloadBytes,long outputBytes,int requestedCells,int nonEmptyCells,int faces,int markers,int translucentFaces) { this(sourceIndexBytes,visibilityBytes,prototypeBytes,atlasPayloadBytes,outputBytes,requestedCells,nonEmptyCells,faces,markers,translucentFaces,0); }
        public Usage { if(sourceIndexBytes<0||visibilityBytes<0||prototypeBytes<0||atlasPayloadBytes<0||outputBytes<0||requestedCells<0||nonEmptyCells<0||faces<0||markers<0||translucentFaces<0||translucentFaces>faces||tintedFaces<0||tintedFaces>faces)throw new IllegalArgumentException("Invalid world mesh usage"); }
        public long knownPayloadBytes() { return sourceIndexBytes+visibilityBytes+prototypeBytes+atlasPayloadBytes+outputBytes; }
    }
    private final WorldModels models;private final WorldVisibility visibility;private final WorldSnapshot.Extent extent;private final WorldSnapshot.Position origin;
    private final float[] vertices,faceBatches;private final int[] indices,translucentFaces;private final double[] translucentCenters;private final List<Cell> cells;private final List<Face> faces;private final List<Marker> markers;
    private final List<AtlasMesh.DrawRange> ranges;private final Optional<BlockMesh.Bounds> bounds;private final Optional<Usage> usage;private final List<WorldProblem> problems;
    private WorldMesh(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,float[] ownedVertices,int[] ownedIndices,int[] translucentFaces,double[] translucentCenters,float[] faceBatches,List<Cell> cells,List<Face> faces,List<Marker> markers,List<AtlasMesh.DrawRange> ranges,Optional<BlockMesh.Bounds> bounds,Optional<Usage> usage,List<WorldProblem> problems) {
        this.models=models;this.visibility=visibility;this.extent=extent;origin=new WorldSnapshot.Position(extent.minX(),extent.minY(),extent.minZ());vertices=ownedVertices;indices=ownedIndices;
        this.translucentFaces=translucentFaces;this.translucentCenters=translucentCenters;this.faceBatches=faceBatches;
        this.cells=List.copyOf(cells);this.faces=List.copyOf(faces);this.markers=List.copyOf(markers);this.ranges=List.copyOf(ranges);this.bounds=bounds;this.usage=usage;this.problems=List.copyOf(problems);
        if(translucentCenters.length!=(long)translucentFaces.length*3)throw new IllegalArgumentException("Invalid translucent center coverage");
        if(faceBatchBytes()!=batchBytes(faces.size()))throw new IllegalArgumentException("Invalid world face-batch coverage");
        if(!problems.isEmpty()&&(vertices.length!=0||indices.length!=0||translucentFaces.length!=0||!cells.isEmpty()||!faces.isEmpty()||!markers.isEmpty()||!ranges.isEmpty()||bounds.isPresent()||usage.isPresent()))throw new IllegalArgumentException("Partial section mesh escaped failure");
        if(usage.isPresent()&&(usage.orElseThrow().translucentFaces()!=translucentFaces.length||usage.orElseThrow().outputBytes()!=packedBytes()+translucentBytes()+faceBatchBytes()))throw new IllegalArgumentException("World mesh payload differs from admitted output");
    }
    static WorldMesh success(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,float[] vertices,int[] indices,int[] translucentFaces,double[] translucentCenters,float[] faceBatches,List<Cell> cells,List<Face> faces,List<Marker> markers,List<AtlasMesh.DrawRange> ranges,Optional<BlockMesh.Bounds> bounds,Usage usage) { return new WorldMesh(models,visibility,extent,vertices,indices,translucentFaces,translucentCenters,faceBatches,cells,faces,markers,ranges,bounds,Optional.of(usage),List.of()); }
    static WorldMesh placeholder(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,List<WorldProblem> problems) { if(problems.isEmpty())throw new IllegalArgumentException("Placeholder requires a problem");return new WorldMesh(models,visibility,extent,new float[0],new int[0],new int[0],new double[0],new float[0],List.of(),List.of(),List.of(),List.of(),Optional.empty(),Optional.empty(),problems); }
    public WorldModels models() { return models; }
    public WorldSnapshot snapshot() { return models.snapshot(); }
    public AtlasLayout layout() { return models.layout(); }
    public WorldVisibility visibility() { return visibility; }
    public WorldSnapshot.Extent extent() { return extent; }
    public WorldSnapshot.Position origin() { return origin; }
    public int vertexCount() { return vertices.length/MeshPacket.STRIDE_FLOATS; }
    public int indexCount() { return indices.length; }
    /** Vertex/index payload only; CPU translucent and face-batch metadata are reported separately. */
    public long packedBytes() { return (long)vertices.length*4+(long)indices.length*4; }
    public int translucentCount() { return translucentFaces.length; }
    public int translucentFace(int index) { return translucentFaces[index]; }
    /** Actual emitted-vertex centroid, relative to this section's exact integer origin. */
    public double translucentCenter(int index,int axis) { Objects.checkIndex(index,translucentFaces.length);Objects.checkIndex(axis,3);return translucentCenters[index*3+axis]; }
    public long translucentBytes() { return (long)translucentFaces.length*Integer.BYTES+(long)translucentCenters.length*Double.BYTES; }
    /** Original face groups of at most BATCH_FACES; bounds use actual emitted section-relative vertices. */
    public int faceBatchCount() { return faceBatches.length/6; }
    public long faceBatchBytes() { return (long)faceBatches.length*Float.BYTES; }
    public float faceBatchMinimum(int batch,int axis) { Objects.checkIndex(batch,faceBatchCount());Objects.checkIndex(axis,3);return faceBatches[batch*6+axis]; }
    public float faceBatchMaximum(int batch,int axis) { Objects.checkIndex(batch,faceBatchCount());Objects.checkIndex(axis,3);return faceBatches[batch*6+axis+3]; }
    public FloatBuffer vertexBuffer() { return FloatBuffer.wrap(vertices).asReadOnlyBuffer(); }
    public IntBuffer indexBuffer() { return IntBuffer.wrap(indices).asReadOnlyBuffer(); }
    public float[] copyVertices() { return vertices.clone(); }
    public int[] copyIndices() { return indices.clone(); }
    public List<Cell> cells() { return cells; }
    public List<Face> faces() { return faces; }
    public List<Marker> markers() { return markers; }
    public List<AtlasMesh.DrawRange> ranges() { return ranges; }
    public Optional<BlockMesh.Bounds> bounds() { return bounds; }
    public Optional<Usage> usage() { return usage; }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
    public RenderBlockState stateForFace(int face) { return snapshot().state(cells.get(faces.get(face).cell()).sourceCell()); }
    public WorldSnapshot.Biome biomeForFace(int face) { return snapshot().biome(cells.get(faces.get(face).cell()).sourceCell()); }
    public WorldSnapshot.Position positionForFace(int face) { return snapshot().extent().position(cells.get(faces.get(face).cell()).sourceCell()); }
    public void requireRaster(AtlasRaster raster) { Objects.requireNonNull(raster);if(requiresPlaceholder()||raster.requiresPlaceholder()||raster.layout()!=layout())throw new IllegalArgumentException("World mesh and raster must share a successful atlas epoch"); }
}
