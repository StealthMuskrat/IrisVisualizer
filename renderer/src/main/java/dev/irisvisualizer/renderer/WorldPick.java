package dev.irisvisualizer.renderer;

import java.util.*;

/** Detached picking receipt. No retained mesh/index/scene graph is needed to inspect a hit. */
public record WorldPick(UUID indexId,WorldSnapshot.Source source,WorldRay ray,Optional<Hit> hit,Stats stats,List<WorldProblem> problems,WorldClip clip) {
    public record Surface(MeshPacket.Face source,double atlasU,double atlasV,double normalX,double normalY,double normalZ) { public Surface { Objects.requireNonNull(source);for(double value:new double[]{atlasU,atlasV,normalX,normalY,normalZ})if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite picking surface"); } }
    public record Hit(WorldSnapshot.Position position,RenderBlockState state,WorldSnapshot.Biome biome,int section,int face,int marker,double distance,double localX,double localY,double localZ,Optional<Surface> surface,List<ModelPlan.Problem> visualProblems) {
        public Hit { Objects.requireNonNull(position);Objects.requireNonNull(state);Objects.requireNonNull(biome);Objects.requireNonNull(surface);visualProblems=List.copyOf(visualProblems);if(section<0||face< -1||marker< -1||(face>=0)==(marker>=0)||surface.isPresent()!=(face>=0)||!Double.isFinite(distance)||distance<0||!Double.isFinite(localX)||!Double.isFinite(localY)||!Double.isFinite(localZ))throw new IllegalArgumentException("Invalid picked native hit"); }
        public boolean placeholder() { return marker>=0; }
    }
    public record Stats(int nodes,int sections,int faces,int markers,int alphaRejected) { public Stats { if(nodes<0||sections<0||faces<0||markers<0||alphaRejected<0)throw new IllegalArgumentException("Invalid picking counts"); } }
    public WorldPick(UUID indexId,WorldSnapshot.Source source,WorldRay ray,Optional<Hit> hit,Stats stats,List<WorldProblem> problems) { this(indexId,source,ray,hit,stats,problems,WorldClip.NONE); }
    public WorldPick { Objects.requireNonNull(clip);Objects.requireNonNull(indexId);Objects.requireNonNull(source);Objects.requireNonNull(ray);Objects.requireNonNull(hit);Objects.requireNonNull(stats);problems=List.copyOf(problems);if(!problems.isEmpty()&&hit.isPresent())throw new IllegalArgumentException("Partial picking hit escaped failure"); }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
