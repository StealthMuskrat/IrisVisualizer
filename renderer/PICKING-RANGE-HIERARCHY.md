# Hierarchical original-range picking

Renderer implementation with compilation only. All 143 renderer/validation sources compile with Java 25/LWJGL; the dependency-free set contains 105 files. Three additional world CPU groups bring the prepared total to 115: 83 CPU/context-free and 32 GPU. The existing world GPU culling group also prepares completed-frame hierarchy comparisons. All assertions remain unexecuted. Native/GPU correctness, performance measurements, total application memory and final integration remain pending; unsupported previews stay disabled.

Current default preparation uses [source-backed range bounds](SOURCE-RANGE-REUSE.md). The flattened leaf/internal allocations described below apply to explicit `RangeStorage.COPY`; SOURCE reads immutable section-local bounds under its independently retained complete-scene lease. Traversal policies, query budgets and deterministic original hits remain the same.

## Rejection inside a section

The section hierarchy remains the first picking stage. Previously every reached section then tested every original face/marker group's bounds. The default index now adds separate balanced hierarchies over those contiguous groups, letting a ray reject an entire subtree before scanning its leaves.

Leaves keep the existing groups of at most 128 original faces or diagnostic markers. Internal nodes store the union of their children in section-relative floats. Only internal bounds are added; leaves share the existing bounds used by the independent linear-group path. Preorder storage derives child offsets from interval sizes, so it needs no child arrays, primitive permutation or copied face geometry. Construction is cancellable, and recursion depth is logarithmic in the bounded per-section group count.

Traversal tests nearer children first and prunes only candidates strictly beyond the current nearest distance. Equal-distance candidates still reach the original deterministic tie rule: section, geometry before marker, then original primitive index. Parent unions and leaves use the existing conservative candidate-box/clip rounding. Actual triangles, barycentric UVs, back-face policy, displayed atlas alpha, exact clipping and marker boxes retain their original tests.

Native states, properties, biome IDs, positions, offsets, UVs, source faces, atlas pages and configured height are unchanged. Bounds are rejection data, never replacement hit identity. Different traversal work can change which query limit is reached; a limit failure still publishes no partial nearest hit.

## Exact preparation admission

`WorldScenePlan.Usage` now records `faceSections` and `markerSections` alongside total groups. Planning counts them during its existing pass; completed scene assembly independently verifies them. Index preparation checks them again against actual meshes before allocating arrays. Older Usage constructors remain available for legacy receipt construction; actual complete scenes receive the full verified counters.

Worker admission remains metadata-only and exact. It does not scan sections on the context/UI thread or reserve hypothetical trees for sections with just one group. Let S be all sections, D drawable sections, F/M face/marker groups, and Sf/Sm the sections containing each primitive family. With hierarchy construction enabled, internal counts are If = F − Sf and Im = M − Sm.

| Payload | Primitive bytes |
| --- | --- |
| Section hierarchy | 60 × (2D − 1), or zero when D = 0 |
| Original group bounds | 24 × (F + M) |
| Internal group bounds | 24 × (If + Im) |
| Original group offsets | 4 × (S + 1) for each nonempty primitive family |
| Internal-tree offsets | 4 × (S + 1) for each family with internal nodes |
| Build-only drawable section IDs | 4 × D |
| Build-only section cursor | 4 when D > 0 |

No internal-tree array or prefix is allocated for an absent internal-node family. Source plus these arrays stays under the existing 16 MiB index/build, 512 MiB combined preparation and local/shared worker ceilings. Successful preparation releases temporary IDs/cursor before retaining source plus exact arrays. Cancelled active work remains charged through unwind. JVM metadata, recursion/object overhead and unrelated application/driver memory remain outside primitive totals.

`Limits.withRangeHierarchy(false)` explicitly builds the original linear-group representation, omitting internal arrays. It preserves every source primitive and still supports the normal query API. There is no automatic downgrade, omitted section, larger allowance or change to native generation.

## Query controls and diagnostics

| Control | Behavior |
| --- | --- |
| Default options/index | Section hierarchy followed by near-first original-group trees |
| `Options.withRangeHierarchy(false)` | Independent linear scan of original groups; ignores the range-node allowance |
| `Options.withBatchCulling(false)` | Exhaustive primitives in reached sections, retaining the section hierarchy |
| Index built without range trees | Linear-group fallback, even if query options request trees |
| `Options.withRangeNodeTests(n)` | Bounds hierarchy work; default 262,144, permitted range 0–1,048,576 |

Old Options/Limits constructors remain available. Clip/culling option-copy helpers preserve the added controls, including completed-frame `pickOptions`. Section-node, face and marker allowances retain their existing independent bounds. A zero range-node allowance can certify an empty source but rejects a required hierarchy test with structured LIMIT and no hit. Disabling hierarchy work explicitly leaves its independent allowance unused.

`Stats.rangeNodes()` counts attempted internal and leaf bound tests, while `culledRangeNodes()` counts missed/clipped/behind-nearest tree candidates. `batches()` and `culledBatches()` describe tested leaves only; descendants skipped by an internal rejection are not visited or counted as tested leaves. Earlier five/seven-argument Stats construction remains available. These are work diagnostics, not runtime performance measurements or native/GPU parity evidence.

## Ownership and prepared coverage

The same index owns all added arrays and its exact CPU scene. Query/close serialization, retained-source admission, displayed-page admission, pending replacement and deferred last-query release remain in force. No new executor, retained query history or source graph in detached hits is introduced.

Three new CPU groups prepare one-section multi-group scenes with two complete height layers, empty interior strips, original markers, mixed geometry/markers, transparent textures and supplied offsets. They compare tree, linear-group and exhaustive results; independently check plane distances, source identities and cross-group ties; verify structural work reduction for known localized/gap rays; and cover exact/deficient budgets, late query exhaustion, cancellation, worker transfer, sparse section counters, zero-payload empty indices and legacy controls.

The existing GPU culling group now prepares the same three query modes against a completed frame's exact atlas and clip, native source identity, option-copy controls and atomic query-budget failure. No renderer contract, GL context, native producer, workflow or pixel comparison was executed for this batch.

[Prepared diagnostic marker bounds](../renderer-gl/MARKER-RANGE-CULLING.md) now let index construction copy original marker leaves without rescanning unit-cell positions. Source journals admit those additional leaf/internal arrays independently of the existing index copies; query policies and detached identities remain unchanged.
