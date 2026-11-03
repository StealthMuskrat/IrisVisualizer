package dev.irisvisualizer.renderer;

import java.util.*;

/** Immutable count pass, permitting scene/cache admission before section output arrays are allocated. */
public final class WorldMeshPlan {
    private final WorldModels models;private final WorldVisibility visibility;private final WorldSnapshot.Extent extent;private final WorldMesher.Limits limits;
    private final Optional<WorldMesh.Usage> usage;private final List<WorldProblem> problems;
    private WorldMeshPlan(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,WorldMesher.Limits limits,Optional<WorldMesh.Usage> usage,List<WorldProblem> problems) {
        this.models=models;this.visibility=visibility;this.extent=extent;this.limits=limits;this.usage=usage;this.problems=List.copyOf(problems);
        if(!problems.isEmpty()&&usage.isPresent())throw new IllegalArgumentException("Partial mesh plan escaped failure");
    }
    static WorldMeshPlan success(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,WorldMesher.Limits limits,WorldMesh.Usage usage) { return new WorldMeshPlan(models,visibility,extent,limits,Optional.of(usage),List.of()); }
    static WorldMeshPlan placeholder(WorldModels models,WorldVisibility visibility,WorldSnapshot.Extent extent,WorldMesher.Limits limits,List<WorldProblem> problems) { if(problems.isEmpty())throw new IllegalArgumentException("Placeholder requires a problem");return new WorldMeshPlan(models,visibility,extent,limits,Optional.empty(),problems); }
    public WorldModels models() { return models; }
    public WorldVisibility visibility() { return visibility; }
    public WorldSnapshot.Extent extent() { return extent; }
    public WorldMesher.Limits limits() { return limits; }
    public Optional<WorldMesh.Usage> usage() { return usage; }
    public List<WorldProblem> problems() { return problems; }
    public boolean requiresPlaceholder() { return !problems.isEmpty(); }
}
