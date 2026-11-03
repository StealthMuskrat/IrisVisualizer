package dev.irisvisualizer.renderer;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

/** Immutable section-local CPU geometry, with exact integer world origin and native picking provenance. */
public final class WorldMesh {
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
    public record Usage(long sourceIndexBytes,long visibilityBytes,long prototypeBytes,long atlasPayloadBytes,long outputBytes,
                        int requestedCells,int nonEmptyCells,int faces,int markers) {
        public Usage { if(sourceIndexBytes<0||visibilityBytes<0||prototypeBytes<0||atlasPayloadBytes<0||outputBytes<0||requestedCells<0||nonEmptyCells<0||faces<0||markers<0)throw new IllegalArgumentException("Invalid world mesh usage"); }
        public long knownPayloadBytes() { return sourceIndexBytes+visibilityBytes+prototypeBytes+atlasPayloadBytes+outputBytes; }
    }
    private final WorldModels models;private final WorldVisibility visibility;private final WorldSnapshot.Extent extent;private final WorldSnapshot.Position origin;
    private final float[] vertices;private final int[] indices;private final List<Cell> cells;private final List<Face> faces;private final List<Marker> markers;
    private final List<AtlasMesh.DrawRange> ranges;private final Optional<BlockMesh.Bounds> bounds;private final Optional<Usage> usage;private final List<WorldProblem> problems;
    private WorldMesh(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,float[] ownedVertices,int[] ownedIndices,List<Cell> cells,List<Face> faces,List<Marker> markers,List<AtlasMesh.DrawRange> ranges,Optional<BlockMesh.Bounds> bounds,Optional<Usage> usage,List<WorldProblem> problems) {
        this.models=models;this.visibility=visibility;this.extent=extent;origin=new WorldSnapshot.Position(extent.minX(),extent.minY(),extent.minZ());vertices=ownedVertices;indices=ownedIndices;
        this.cells=List.copyOf(cells);this.faces=List.copyOf(faces);this.markers=List.copyOf(markers);this.ranges=List.copyOf(ranges);this.bounds=bounds;this.usage=usage;this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&(vertices.length!=0||indices.length!=0||!cells.isEmpty()||!faces.isEmpty()||!markers.isEmpty()||!ranges.isEmpty()||bounds.isPresent()||usage.isPresent()))throw new IllegalArgumentException("Partial section mesh escaped failure");
    }
    static WorldMesh success(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,float[] vertices,int[] indices,List<Cell> cells,List<Face> faces,List<Marker> markers,List<AtlasMesh.DrawRange> ranges,Optional<BlockMesh.Bounds> bounds,Usage usage) { return new WorldMesh(models,visibility,extent,vertices,indices,cells,faces,markers,ranges,bounds,Optional.of(usage),List.of()); }
    static WorldMesh placeholder(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,List<WorldProblem> problems) { if(problems.isEmpty())throw new IllegalArgumentException("Placeholder requires a problem");return new WorldMesh(models,visibility,extent,new float[0],new int[0],List.of(),List.of(),List.of(),List.of(),Optional.empty(),Optional.empty(),problems); }
    public WorldModels models() { return models; }
    public WorldSnapshot snapshot() { return models.snapshot(); }
    public AtlasLayout layout() { return models.layout(); }
    public WorldVisibility visibility() { return visibility; }
    public WorldSnapshot.Extent extent() { return extent; }
    public WorldSnapshot.Position origin() { return origin; }
    public int vertexCount() { return vertices.length/MeshPacket.STRIDE_FLOATS; }
    public int indexCount() { return indices.length; }
    public long packedBytes() { return (long)vertices.length*4+(long)indices.length*4; }
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
