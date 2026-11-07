# Scalar inspection hierarchy and visited-face budgets

Renderer implementation with compilation only. All 143 renderer/validation sources compile with Java 25/LWJGL; the dependency-free set contains 105 files. Three additional CPU groups bring the prepared total to 118: 86 CPU/context-free and 32 GPU. The existing scalar GPU group also prepares completed-frame hierarchy/budget checks. All assertions remain unexecuted. Native/GPU fidelity, performance measurements, total application memory and final integration remain pending; unsupported previews stay disabled.

## Immutable bounds shared with geometry

`NoiseSurface` now builds balanced internal bounds over its original groups of at most 1,024 rectangles. Both CELLS and GREEDY modes retain their original face order and complete native scalar volume. Leaves use the existing six-integer grid bounds; internal nodes store exact integer unions. Preorder storage derives child offsets from interval sizes, requiring no child arrays, face permutation or geometry copy.

The hierarchy is built during ordinary surface preparation, with cancellation checks before each internal union. Empty or single-group surfaces need no internal nodes. Recoloring shares the same cells, spans, original group bounds and hierarchy arrays; `sharesGeometry` includes their exact identity. New colors use the original native values, and earlier surfaces/frames remain immutable.

| Retained output | Primitive bytes |
| --- | --- |
| CELLS face | 9: cell index, side and color |
| GREEDY rectangle | 13: cell index, side, span and color |
| Original group bounds | 24 per nonempty group |
| Internal hierarchy | 24 × max(0, group count − 1) |

The one-million-face hard ceiling permits at most 1,024 groups and 1,023 internal nodes: at most 24,552 added primitive bytes. `hierarchyBytes`, `batchHierarchyCount` and checked minimum/maximum accessors describe this immutable output. Original `batchBytes` still reports leaf bounds only. `Usage.meshBytes`, retained payload, prospective meshing peaks, worker admission, histogram source estimates and GPU-source admission include both arrays. Constructor checks verify output coverage and the admitted primitive receipt.

Existing count passes include hierarchy demand before allocating output arrays. Greedy plane scratch still overlaps the complete output allowance. A deficit returns structured failure with no partial surface, rather than omitting samples or configured height. Recolor reservations include shared hierarchy payload and replacement colors. Existing working/face ceilings are unchanged; JVM metadata, stack/object overhead and unrelated native/driver memory remain outside primitive estimates.

## Query modes and budgets

`NoisePick` normally visits nearer hierarchy children first. It uses the same outward-rounded candidate slabs for integer parent unions and original leaves, and prunes only groups strictly beyond the current nearest distance. Actual front-face planes, closed clip/ray endpoints, rectangle-to-original-sample mapping and original sample/direction ties retain their existing tests. No Minecraft block or biome identity is fabricated.

| Option | Contract |
| --- | --- |
| Existing `new Options(maximumFaces[, clip])` | Preserve whole-source face allowance before traversal; enable hierarchy by default |
| `withVisitedFaceBudget(maximum)` | Opt in to charging only attempted primitives reached after rejection |
| `withRangeHierarchy(false)` | Independent linear scan of original group bounds |
| `withBatchCulling(false)` | Exhaustive original rectangles, with no range-node work |
| `withRangeNodeTests(n)` | Bound internal/leaf hierarchy tests; default/hard maximum 2,047 |

The visited-face mode allows a localized query on a large complete surface under a smaller primitive allowance. It does not claim a nearest hit when the remaining allowance is insufficient. A zero face allowance can certify a rejected whole-tree miss; a zero node allowance can certify an empty source. A required but disallowed test returns LIMIT with no partial nearest hit. Linear/exhaustive reference modes ignore the hierarchy-node allowance but retain their selected face-budget policy.

Both traversal modes and all budget controls survive `withClip`, other option-copy helpers, pointer-worker requests and completed-frame `pickOptions`. The frame still supplies its actual geometric clip and exact displayed surface; caller controls cannot substitute unrelated source samples or bypass source ownership.

## Diagnostics and ownership

`Result.rangeNodes` and `culledRangeNodes` describe attempted hierarchy bounds and rejected nodes. Existing visited/culled batch counters describe tested leaves only; leaves skipped through an internal rejection are not visited. Query-limit failures now retain bounded work diagnostics while withholding the hit. Attempt counters include the first disallowed attempt, so failure diagnostics can exceed a caller's configured allowance by one without performing that test. Legacy whole-source preflight rejection still reports zero traversal work. Earlier Options and Result constructors remain available.

The hierarchy lives inside the same admitted immutable surface. Pointer requests charge its complete source payload, keep one active/latest pending job and retain charges until cancellation/unwind. Successful detached results contain original scalar/sample/grid receipts rather than source arrays or GL owners. Surface worker output shrinks from preparation peak to the exact retained surface after its stack unwinds. No executor, retained request history, sampling algorithm, native engine call or readiness override is added.

GPU geometry/color buffers and GPU-copy behavior are unchanged. They retain the larger accounted CPU surface; recolor still proves shared geometry in constant time. The initial hierarchy implementation applied these bounds to CPU inspection. The [draw-preparation path](../renderer-gl/HIERARCHICAL-SCALAR-CULLING.md) now also consumes them, with separate linear and disabled reference modes. Neither path establishes driver/pixel equivalence.

## Prepared deferred coverage

Three new CPU groups prepare CELLS/GREEDY tree, linear-group and exhaustive comparisons over full-height checkerboard fields, interior absent strips, uniform merged rectangles, clips, shared edges and shifted anchors. Independent top-plane/sample expectations supplement work-reduction cases, legacy/visited allowance boundaries, empty/zero queries, atomic failure diagnostics, exact/deficient mesh peaks, recolor sharing and controlled surface/pointer worker ownership, cancellation and frame tokens.

The existing scalar GPU group now prepares the same query modes with a completed frame's clip, exact original scalar, inherited visited-face policy and atomic budget failure. No renderer contract, worker/query execution, GL context, native producer, workflow or runtime pixel comparison was executed for this batch.
