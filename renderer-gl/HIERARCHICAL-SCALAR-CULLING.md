# Hierarchical scalar draw preparation

Renderer implementation with compilation only. All 144 renderer/validation sources compile with Java 25/LWJGL; the dependency-free set contains 106 files. One additional context-free math group brings the prepared total to 119: 87 CPU/context-free and 32 GPU. The existing scalar GPU group also prepares three-mode pixel and work comparisons. All assertions remain unexecuted. Native/GPU correctness, performance measurements, total application memory and final integration are pending; unsupported previews stay disabled.

## Reuse admitted scalar bounds

Scalar surfaces already retain [balanced internal bounds](../renderer/SCALAR-RANGE-HIERARCHY.md) over their original groups for CPU picking. Draw preparation now consumes the same immutable integer unions, without rescanning samples, rebuilding geometry or creating another hierarchy. Recoloring continues to share these bounds through the exact geometry-identity proof.

`GlNoiseCulling` classifies the root and descends only when needed:

| Parent classification | Group decisions |
| --- | --- |
| Conservatively outside frustum or clip | Reject the entire original interval |
| Wholly inside frustum and clip | Accept the entire original interval |
| Intersecting/uncertain | Classify children, or run the original leaf test |

Accepted/rejected intervals fill the existing one-byte-per-group decision mask. Drawing still walks that mask and coalesces consecutive visible original face runs. Bulk classification saves bound tests, not every group-memory access or GPU face operation. No face is reordered, merged, dropped from the source, or represented by a different sample.

## Conservative float predicates

Internal and leaf bounds use identical float grid multiplication and float anchor addition. Valid positive scale makes these transforms monotone, so a transformed parent union encloses each transformed leaf. The existing maximum-support frustum rejection and expanded geometric-clip intersection remain the rejection policy. Parent enclosure also bounds the magnitude-dependent conservative margins.

Whole-parent acceptance uses a stricter proof: the unexpanded parent must lie inside the actual clip bounds, and its minimum support distance must be nonnegative for every frustum plane. It does not treat expanded intersection as containment. Every enclosed leaf therefore survives the original conservative leaf predicate. Uncertain or boundary cases descend to that predicate.

Camera/scale/spacing checks remain active for every strategy. Unsupported float precision still fails explicitly before target modification rather than moving anchors or reducing samples/full configured height. These are source-level conservative predicates, not native pixel-parity evidence.

## Explicit strategies and receipts

`GlCullingMode` is a context-free public enum used by typed overloads on `GlNoiseRenderer.render`, `drawScratchBytes` and `GlNoiseFrame.render`:

| Mode | CPU group preparation |
| --- | --- |
| `HIERARCHY` | Parent rejection/acceptance followed by uncertain children/leaves |
| `LINEAR` | Independent original leaf scan using the same conservative predicates |
| `NONE` | Keep every original group; fragment clipping and precision checks remain active |

Existing boolean/convenience calls remain available: true/default selects hierarchy and false selects none. The connected scalar view inherits the default. Each immutable-frame call still owns a separate target and preserves original volume/context/grid, display style, camera, clip and readback/picking receipts. Null strategies reject before journal or target admission.

`GlNoiseRenderer.Stats.boundsTests` records actual bound classifications, including internal nodes. `testedBatches` records leaves whose bounds were tested. Visible/culled batch and face totals include descendants classified in bulk. Earlier three/seven-argument Stats construction remains available with zero new work counters. New counters describe structural work rather than wall-clock timing; hierarchy can still perform more classifications than linear traversal in intersecting cases.

## Scratch, ownership and build gates

Hierarchical and linear preparation use the same existing scratch allowance: 1,024 bytes for bounded math plus one byte per original group. None omits that mask. Traversal uses bounded recursion over at most 1,024 groups, one fixed counter object and the already admitted source hierarchy; it allocates no per-node bounds, permutation, stack array, GL name or GPU storage. JVM object/stack, host-state and driver overhead retain their existing exclusions.

The renderer admits scratch before constructing helper arrays, changing host state or writing target pixels. Helper owners unwind before the covering journal retires. Pre-draw rejection preserves existing target completeness and host state. Successful frame publication still requires draw, host restoration and scratch cleanup. Original device/coordinator limits, retirement/quarantine behavior and worker ownership remain in force.

The compile-only PowerShell runner now includes `GlCullingMode` among its seven context-free GL helpers/types. This remains a source-inspected runner change; no PowerShell/Gradle task or contract execution ran here. Production source count is 138: 94 CPU and 44 GL. Five harnesses and one fixture bring the full set to 144 files.

## Prepared deferred coverage

The new math group prepares CELLS/GREEDY fields at three scales, randomized camera/FOV/aspect/clip/rebased-anchor cases, exact agreement with every independent leaf, a direct homogeneous-corner oracle, reverse mask lookup and complete face coverage. Analytic disjoint/contained cases prepare one-root rejection/acceptance, no leaf tests and unchanged scratch demand. Existing empty, partial last-group and invalid precision/index cases remain compiled alongside it.

The existing GPU group prepares separate hierarchical/linear/disabled clipped frames, exact differential pixels and submitted totals, disjoint/contained source cases, new work receipts, legacy receipt construction, null-mode rejection, scratch deficits and prior target/state preservation. Actual renderer/native/GPU execution and final editor adoption remain deferred.
