package dev.irisvisualizer.renderer;

import java.util.*;

/** Bounded local-block CPU geometry. This result grants no preview or world-generation readiness. */
public final class BlockMesh {
    /** CELL is explicit native geometry anchored to the cell, independent of its JSON model offset. */
    public enum Placement { MODEL, CELL }
    public record Vertex(float x,float y,float z,float u,float v) {
        public Vertex { if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||!Float.isFinite(u)||!Float.isFinite(v))throw new IllegalArgumentException("Nonfinite mesh vertex"); }
    }
    public record Selection(ModelPlan.Source dispatch,int sourceIndex,int ticket,AssetId model,int x,int y,int z,boolean uvlock) {
        public Selection { Objects.requireNonNull(dispatch);Objects.requireNonNull(model);if(sourceIndex<0||ticket<0||!ModelPlan.quarter(x)||!ModelPlan.quarter(y)||!ModelPlan.quarter(z))throw new IllegalArgumentException("Invalid mesh selection"); }
    }
    public record Quad(ModelPlan.Source source,int selection,ModelPlan.Direction modelFace,Optional<ModelPlan.Direction> cullface,
                       ModelPlan.Vec3 normal,ModelPlan.Material material,int tintIndex,int lightEmission,
                       boolean ambientOcclusion,Optional<ModelPlan.Direction> shadeDirectionOverride,List<Vertex> vertices,Optional<NativeMeshSource> nativeSource,Placement placement) {
        public Quad(ModelPlan.Source source,int selection,ModelPlan.Direction modelFace,Optional<ModelPlan.Direction> cullface,ModelPlan.Vec3 normal,ModelPlan.Material material,int tintIndex,int lightEmission,boolean ambientOcclusion,Optional<ModelPlan.Direction> shadeDirectionOverride,List<Vertex> vertices) { this(source,selection,modelFace,cullface,normal,material,tintIndex,lightEmission,ambientOcclusion,shadeDirectionOverride,vertices,Optional.empty(),Placement.MODEL); }
        public Quad(ModelPlan.Source source,int selection,ModelPlan.Direction modelFace,Optional<ModelPlan.Direction> cullface,ModelPlan.Vec3 normal,ModelPlan.Material material,int tintIndex,int lightEmission,boolean ambientOcclusion,Optional<ModelPlan.Direction> shadeDirectionOverride,List<Vertex> vertices,Optional<NativeMeshSource> nativeSource) { this(source,selection,modelFace,cullface,normal,material,tintIndex,lightEmission,ambientOcclusion,shadeDirectionOverride,vertices,nativeSource,Placement.MODEL); }
        public Quad {
            Objects.requireNonNull(source);Objects.requireNonNull(modelFace);Objects.requireNonNull(cullface);Objects.requireNonNull(normal);Objects.requireNonNull(material);Objects.requireNonNull(shadeDirectionOverride);
            Objects.requireNonNull(nativeSource);Objects.requireNonNull(placement);if(placement==Placement.CELL&&nativeSource.isEmpty())throw new IllegalArgumentException("Cell placement requires explicit native provenance");vertices=List.copyOf(vertices);if((nativeSource.isPresent()?source.kind()!=ClientAssetSnapshot.Kind.BLOCKSTATE||selection!=-1:source.kind()!=ClientAssetSnapshot.Kind.MODEL||selection<0)||vertices.size()!=4||tintIndex< -1||lightEmission<0||lightEmission>15)throw new IllegalArgumentException("Invalid mesh quad metadata");
        }
    }
    public record Bounds(ModelPlan.Vec3 min,ModelPlan.Vec3 max) {
        public Bounds { Objects.requireNonNull(min);Objects.requireNonNull(max);if(min.x()>max.x()||min.y()>max.y()||min.z()>max.z())throw new IllegalArgumentException("Reversed mesh bounds"); }
    }
    private final ClientAssetSnapshot.Receipt assets;private final RenderBlockState state;private final List<Selection> selections;
    private final List<Quad> quads;private final Optional<Bounds> bounds;private final Optional<MeshPacket> packet;private final List<ModelPlan.Problem> problems;private final Optional<NativeMeshSource> nativeSource;
    private BlockMesh(ClientAssetSnapshot.Receipt assets,RenderBlockState state,List<Selection> selections,List<Quad> quads,
                      Optional<Bounds> bounds,Optional<MeshPacket> packet,List<ModelPlan.Problem> problems,Optional<NativeMeshSource> nativeSource) {
        this.assets=Objects.requireNonNull(assets);this.state=Objects.requireNonNull(state);this.selections=List.copyOf(selections);this.quads=List.copyOf(quads);
        this.bounds=Objects.requireNonNull(bounds);this.packet=Objects.requireNonNull(packet);this.problems=List.copyOf(problems);this.nativeSource=Objects.requireNonNull(nativeSource);
        if(!problems.isEmpty() && (!quads.isEmpty() || packet.isPresent() || bounds.isPresent() || !selections.isEmpty()))throw new IllegalArgumentException("Partial geometry must not escape a failed bake");
    }
    static BlockMesh success(ModelPlan plan,List<Selection> selections,List<Quad> quads,Optional<Bounds> bounds,MeshPacket packet) {
        return new BlockMesh(plan.assets(),plan.state(),selections,quads,bounds,Optional.of(packet),List.of(),Optional.empty());
    }
    static BlockMesh captured(ClientAssetSnapshot.Receipt assets,RenderBlockState state,List<Selection> selections,List<Quad> quads,Optional<Bounds> bounds,MeshPacket packet,NativeMeshSource nativeSource) { return new BlockMesh(assets,state,selections,quads,bounds,Optional.of(packet),List.of(),Optional.of(nativeSource)); }
    static BlockMesh placeholder(ClientAssetSnapshot.Receipt assets,RenderBlockState state,List<ModelPlan.Problem> problems) {
        if(problems.isEmpty())throw new IllegalArgumentException("Placeholder requires a problem");return new BlockMesh(assets,state,List.of(),List.of(),Optional.empty(),Optional.empty(),problems,Optional.empty());
    }
    static BlockMesh placeholder(ModelPlan plan,List<ModelPlan.Problem> problems) {
        if(problems.isEmpty())throw new IllegalArgumentException("Placeholder requires an explicit problem");
        return placeholder(plan.assets(),plan.state(),problems);
    }
    public ClientAssetSnapshot.Receipt assets() { return assets; }
    public RenderBlockState state() { return state; }
    public List<Selection> selections() { return selections; }
    public List<Quad> quads() { return quads; }
    public Optional<NativeMeshSource> nativeSource() { return nativeSource; }
    public Optional<Bounds> bounds() { return bounds; }
    public Optional<MeshPacket> packet() { return packet; }
    public List<ModelPlan.Problem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
