# Implicit world section culling

Implemented 2026-11-07. Java 25 compilation passes for both renderer source sets (144 full, 106 dependency-free). Two new context-free groups and one GPU group are prepared, bringing totals to 109 context-free and 51 GPU groups, 160 overall. All remain unexecuted under the renderer implementation gate. Native compatibility, driver correctness, performance and final integration remain pending; unsupported previews stay disabled.

## Grid envelopes without retained nodes

`WorldScenePlan` emits complete regular tiles with X fastest, then Z, then Y. `WorldScene` retains that verified original order. `GlWorldCulling` splits contiguous section intervals at their midpoint and derives each interval's envelope from the existing offsets and grid dimensions. Crossing an X row uses the complete X origin range; crossing a Y slab uses the complete Z origin range. Y uses the first and last origins. Partial boundary tiles and empty sections retain their original positions.

The mesher's existing absolute local-coordinate ceiling of 128 is now named `WorldMesher.MAX_LOCAL_COORDINATE`; the accepted geometry range has not changed. Each interval expands its origin range by that ceiling, then rounds outward with `nextDown`/`nextUp`. This encloses both the original section predicate's double offset addition and the shader's float addition, including model overhangs and diagnostic bounds. It is intentionally wider than an actual mesh union. There is no second source walk or new section/node bounds array. Existing face and marker prefix counts identify intervals containing no drawable data.

Complete scene/camera precision admission still runs first, and every section offset is still prepared and checked, including empty or hidden sections. This optimization does not reduce source capture, full-height meshing, upload coverage or memory admission.

## Conservative classification and reference modes

| Mode | Section decisions |
| --- | --- |
| `HIERARCHY` (default / boolean true) | Skip empty intervals, classify parents, and descend on uncertainty |
| `LINEAR` | Apply the original section predicate independently to every nonempty section |
| `NONE` (boolean false) | Apply the same original section predicate; disable face/marker group rejection |

Parents reject only under the existing outward frustum tolerance or disjoint clip intersection with four float ULPs plus 1e-6. Their enclosing coordinate magnitudes cannot reduce those allowances. Acceptance requires strict unexpanded frustum and clip containment. A single-section leaf always uses the original double frustum and float clip predicates. Accepted intervals mark only original populated sections; rejected intervals leave them false. Face and marker group preparation then follows its existing path, and transparent order entries retain their original source keys and relative order.

`FrameStats.sectionBoundsTests` reports actual parent plus leaf classifications, and `sectionLeafTests` reports only original leaf predicates. A contained or disjoint populated source can need one parent test and zero leaf tests; a wholly empty source needs none. Work is bounded by `2 * totalSections - 1`, with leaves bounded by nonempty sections. All older `FrameStats` constructors remain available, including the previous 21-argument constructor, and default new receipts to zero. Record equality now includes the two new counters.

Hierarchy and linear modes admit exactly the same temporary primitive payload. The two grid dimensions and bounded recursion are bookkeeping under the existing policy; no additional GPU names, buffers or journals are created. Accepted-mask filling and later submission still visit source sections. Loose envelopes and intersecting trees may require more tests than a linear scan. No measured speedup or constant-time total preparation is claimed.

## Prepared validation and limits

The CPU-only fixture now supports a 9 by 5 by 9 volume tiled into 18 sections, with separate mesh, marker/mesh, empty/mesh and fully empty variants. Native IDs, states, biomes and long-coordinate origins remain unchanged. A transparent variant exercises global ordering on the GPU path.

Two context-free groups cover every nonempty contiguous interval and axis, double and shader-style float enclosure, X-row/Y-slab crossings, irregular extents, near-limit/rebased anchors, invalid ranges, unchanged scratch sizing and precision rejection. Fixed-seed camera/clip trials compare every section mask and face/marker decision with linear reference predicates. Independent interval classifications must agree with all enclosed original leaves; root acceptance/rejection and bounded work receipts are also prepared.

The GPU group prepares hierarchy/linear/disabled independent frames with exact pixel comparisons, original camera/source/clip receipts, index/transparent/marker counts, root work, old constructor compatibility, unchanged draw scratch and a one-byte-short admission deficit preserving previous output and host state. Its report guard now requires 51 groups. No shader, GL context, test main, Windows runner, Gradle validation task or workflow was executed for this batch.
