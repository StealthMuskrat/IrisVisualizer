# Hierarchical world face-group drawing

Renderer implementation with compilation only. All 144 renderer/validation sources compile with Java 25 and LWJGL 3.4.1; the dependency-free set contains 106 files. Three new prepared groups bring the total to 122: 90 CPU/context-free and 32 GPU. The existing world GPU group now prepares three-mode differential frames and root-acceptance checks. No assertions, renderer harnesses, shaders, native adapters or GL contexts were executed. Native/GPU correctness, performance measurements, total application memory and final editor integration remain pending; unsupported previews stay disabled.

## Prepared source bounds

`WorldMesher` now builds a balanced tree over the original 128-face groups after emitting complete section geometry. Each internal node stores six floats: the exact component-wise union of its two children. Leaves remain in the existing face-batch array. Internal nodes use preorder storage, with no child/permutation arrays, and cover contiguous original face intervals. Empty, marker-only and single-group sections require no internal bounds payload.

For a section with `g` groups, added payload is `24 * max(0, g - 1)` bytes. The hard section face ceiling of 1,048,576 permits at most 8,192 groups and 196,584 additional bytes, before the existing mesh/working ceilings impose any tighter limit. Traversal depth is bounded by that group ceiling. Construction checks cancellation at each internal node; cancellation or count/admission failure publishes no partial mesh.

The count pass includes internal bounds before output allocation. `WorldMesh.Usage.outputBytes`, complete scene planning/emission, cache retention and existing shared/source journals inherit those exact bytes. Mesh publication independently checks the admitted payload against packed geometry, translucent metadata, leaf bounds and internal bounds. Existing memory ceilings are unchanged; a former exact-boundary request may now fail explicitly. JVM object/array/stack and driver overhead retain their existing exclusions.

`WorldMesh.faceBatchHierarchyCount`, `faceBatchHierarchyBytes`, `faceBatchHierarchyMinimum` and `faceBatchHierarchyMaximum` expose immutable values and accounting. Material-copy/reused geometry retains the exact CPU scene and its bounds. This source hierarchy is distinct from the independently admitted picking index's bounds; both source and index payloads remain charged when retained. GPU geometry/material/index buffers, transfer completion totals and shader layouts are unchanged.

## Conservative draw classification

Whole-section frustum/clip rejection remains the first step, including marker-only sections. For each remaining section with faces, hierarchical preparation classifies its face root:

| Classification | Original group decisions |
| --- | --- |
| Outside frustum or active clip under the conservative rejection predicate | Reject the entire interval |
| Strictly contained by frustum and active clip | Accept the entire interval |
| Intersecting or uncertain | Descend to children and ultimately the original leaf predicate |

Both parent and leaf bounds use the geometry shader's float section-offset addition. Float addition is monotone over the supported finite coordinates, so translated parent unions enclose every translated leaf. Maximum-support frustum tests retain their outward numerical allowance. World clip intersection retains four float ULPs at the translated bound magnitude plus 1e-6; larger enclosing magnitudes cannot reduce this allowance.

Acceptance uses unexpanded clip containment and a nonnegative minimum-support distance for every frustum plane. Expanded clip intersection alone never proves acceptance. The shared strict containment helper also preserves the scalar path's existing behavior and separate eight-ULP scalar clip policy. These source-level predicates do not establish native or boundary-pixel parity.

Accepted/rejected intervals fill the existing byte-per-group mask. Solid material ranges still coalesce adjacent surviving original index spans. The globally sorted transparent pass consults the same mask without changing source keys, relative order or alpha handling. Markers retain their existing whole-section and fragment clipping path. Full native state/biome/cell identity, face provenance, UVs, colors and supplied final lighting remain intact.

## Comparison modes and work receipts

Typed overloads on `GlRenderer.renderFrame`, `drawScratchBytes` and `GlFrame.render` accept the existing `GlCullingMode`:

| Mode | World face-group work |
| --- | --- |
| `HIERARCHY` | Classify visible-section trees before drawing; fill complete accepted/rejected intervals |
| `LINEAR` | Independently test original leaf bounds lazily, once per group across all material passes |
| `NONE` | Keep all original groups after the common section preflight; fragment clipping remains active |

Existing boolean/convenience methods remain: true/default selects hierarchy and false selects none. Connected world views inherit the default. Every frame still owns an independent target and carries its original source/camera/clip/raster/picking/readback receipt. Null strategies reject before journal/target admission or mutation.

`FrameStats.faceBoundsTests` records actual parent plus leaf classifications. `faceBatchTests` records only actual leaf tests, so a wholly contained multi-group section can report one bound test and zero leaf tests. `culledFaceBatches` includes descendants rejected in bulk and can exceed tested leaves in hierarchical mode. Existing solid-range intersection, skipped solid/translucent face and submission counters retain their meanings. Older ten/thirteen/sixteen-argument constructors remain available with zero new bound-work metadata; the sixteen-argument constructor retains its previous leaf/rejection consistency check.

Hierarchy and linear modes admit the same temporary payload: 1,024 bytes of bounded math, section offsets/visibility, section group prefixes and one byte per original group. None omits prefixes and group masks. Hierarchical traversal uses existing source bounds and bounded recursion; no extra per-node arrays, GPU names or storage are created during drawing. The covering journal is admitted before helper construction or target writes and retires after helper owners unwind. Cleanup failures retain existing quarantine/accounting behavior, and only successful draw/restoration/cleanup publishes a complete frame.

The mask fill and draw traversal still visit original groups. Hierarchy reduces bound classifications in favorable cases; it may perform more classifications than linear traversal for intersecting trees. No wall-clock speedup or constant-time total draw preparation is claimed.

## Prepared deferred coverage

Two new CPU groups prepare independent direct emitted-vertex bounds oracles, shifted native placements, sparse geometry, mixed/marker-only/empty sections, 126/132/186-face partial-group boundaries, original state provenance, retained/cache sharing, exact and one-byte-short section/scene limits, and late hierarchy cancellation without partial output.

One new context-free math group prepares 128 deterministic randomized camera/FOV/aspect/clip/rebased-anchor comparisons against independent leaf and lazy linear predicates, a direct homogeneous-corner interior oracle, complete reverse/forward mask lookup, bounded work counters, wholly contained root acceptance, clipped subtree rejection, marker-only behavior and null-mode guards. The prior world group explicitly retains LINEAR mode to preserve its independent lazy-cache checks.

The existing GPU group prepares separate hierarchical, linear and disabled immutable frames, differential pixels and exact submitted face/index counts for transparent and mixed scenes, bulk/leaf work receipts, a contained-root frame pair, unchanged scratch sizing, memory deficits, null-mode preflight, prior completed target/host-state preservation and exact displayed-source picking. All 122 prepared groups remain unexecuted until renderer implementation is complete. Production source inventory remains 138: 94 CPU and 44 GL, plus five harnesses and one fixture. No PowerShell/Gradle task or Windows validation ran for this batch.
