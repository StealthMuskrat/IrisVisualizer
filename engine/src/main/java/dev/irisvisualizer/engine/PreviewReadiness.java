package dev.irisvisualizer.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** World-preview readiness. File operations and editor workflows never depend on this gate. */
public final class PreviewReadiness {
    private PreviewReadiness() {}
    /** Capabilities are supplied by the backend and verified evidence, never by editable pack settings. */
    public record Capabilities(boolean completeNativeWorld, boolean matchingIndependentBaseline,
                               boolean totalMemoryBudgetConfirmed) {}
    public record NativePlacement(String resourceType, String resourceKey, String propertyPath, String structureKey) {
        public NativePlacement {
            Objects.requireNonNull(resourceType); Objects.requireNonNull(resourceKey);
            Objects.requireNonNull(propertyPath); Objects.requireNonNull(structureKey);
        }
    }
    public enum Code { NATIVE_WORLD_INCOMPLETE, NATIVE_PLACEMENT_INCOMPLETE, NATIVE_BASELINE_MISSING, MEMORY_BUDGET_UNCONFIRMED }
    public record Issue(Code code, String resourceType, String resourceKey, String propertyPath, String message) {}
    public record Result(List<Issue> blockers) {
        public Result { blockers = List.copyOf(blockers); }
        public boolean previewAllowed() { return blockers.isEmpty(); }
    }
    public static Result evaluateWorld(String dimensionKey, Capabilities capabilities, List<NativePlacement> placements) {
        Objects.requireNonNull(dimensionKey); Objects.requireNonNull(capabilities); Objects.requireNonNull(placements);
        List<Issue> issues = new ArrayList<>();
        if (!capabilities.completeNativeWorld()) {
            issues.add(new Issue(Code.NATIVE_WORLD_INCOMPLETE, "dimensions", dimensionKey, "",
                    "The backend does not complete native world structure and feature generation."));
            for (NativePlacement placement : placements) {
                issues.add(new Issue(Code.NATIVE_PLACEMENT_INCOMPLETE, placement.resourceType(), placement.resourceKey(),
                        placement.propertyPath(), "Native placement requires the complete native world path: " + placement.structureKey()));
            }
        }
        if (!capabilities.matchingIndependentBaseline()) issues.add(new Issue(Code.NATIVE_BASELINE_MISSING,
                "dimensions", dimensionKey, "", "No independent complete native baseline matches the pinned inputs and preview context."));
        if (!capabilities.totalMemoryBudgetConfirmed()) issues.add(new Issue(Code.MEMORY_BUDGET_UNCONFIRMED,
                "dimensions", dimensionKey, "", "Total generation, native and mesh memory budgets are not confirmed."));
        return new Result(issues);
    }
}
