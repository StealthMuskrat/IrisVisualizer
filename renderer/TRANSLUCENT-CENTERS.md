# Prepared translucent face centers

Renderer implementation, 2026-11-05. Camera sorting now reads compact per-section translucent entries rather than scanning every source face and rereading four vertices for every camera pose. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Runtime, concurrency, native and real-driver validation remain deferred until renderer implementation is complete; unsupported previews remain disabled.

## Assembly and identity

`WorldMesher` counts surviving translucent faces after the existing native visibility mask. Count-only plans admit the complete primitive payload before emission allocates its exact-sized arrays. During emission, each translucent face records its original emitted face index and double XYZ centroid. Hidden faces, opaque/cutout faces and diagnostic markers produce no entries.

Centers use the actual four section-relative float positions after model/fluid placement and float rounding. Each coordinate is accumulated in original vertex order in a double, then divided by four, matching the former camera-sort calculation. No absolute world position is converted to float, and no center is derived from an assumed cube or source-model bounds.

`WorldMesh.translucentCount()`, `translucentFace(index)` and `translucentCenter(index, axis)` expose individual immutable entries. Primitive arrays remain private. `Usage.translucentFaces()` records the admitted count; emission verifies count agreement and exact output-byte coverage before publishing. Native state/biome IDs, cell/prototype provenance, face/index/UV order and original geometry remain intact.

## Camera preparation

`WorldDrawOrder` uses the [verified complete-scene translucent total](MATERIAL-COUNTS.md) before allocating its sorting arrays. Source-plus-scratch admission uses this actual count under existing ceilings; preparation still verifies section entry coverage. Depth preparation visits only those entries; it still checks every section's exact anchor subtraction and relative-coordinate precision, including sections without translucent geometry. It uses the same camera-forward depth expression and section/face key as before. Stable merge sorting and deterministic equal-depth ties remain unchanged.

Repeated poses therefore avoid opaque/cutout face scans and vertex-buffer reads during depth preparation. This changes the work performed, without a measured timing claim. Sorting remains global back-to-front quad-centroid ordering; intersecting or cyclic transparent surfaces still require a different rendering solution. It introduces neither OIT nor per-pixel native parity.

## Direction preflight before replacement and scratch

`WorldOrderWorker.submit` now checks the supplied eye-to-target direction before job/admission creation or cancellation of earlier work. A finite nonzero direction shorter than 1e-8 is rejected synchronously, preserving the exact previous revision/status, pending or READY order and local/shared reservation totals. Fresh rejected scene wrappers are still consumed; the already-owned-wrapper guard runs first and preserves that existing owner. A corrected submission can use the next ordinary revision without a rejected request spending a token.

Direct `WorldDrawOrder.prepare` keeps its structured COORDINATE problem and empty failed order, but now checks direction before allocating the key/depth scratch arrays. Existing face-count/sort/combined-limit checks still precede that direct pose check, preserving their diagnostic precedence. Direction length uses nested `Math.hypot`, matching `GlCamera.basis`, with the existing inclusive 1e-8 minimum. The requested vector, source entries, merge algorithm, section/face tie rule and memory ceilings are unchanged; no face scan or source-sized scratch is added by worker preflight.

A prepared world contract covers tiny, just-below-minimum and subnormal directions, fresh-wrapper consumption, already-owned protection, pending/READY preservation, corrected revision progression, exact-threshold acceptance, axial-order equivalence and final account release. It compiles but remains unexecuted. All 64 prepared groups and wider native/GPU/order comparisons remain deferred until renderer-stage validation.

## CPU and GPU accounting

| Payload | Primitive bytes |
| --- | --- |
| Original packed quad vertices/indices | 152 per emitted face |
| Retained translucent index and double XYZ center | 28 per translucent face |
| [Prepared face bounds](../renderer-gl/WORLD-RANGES.md) | 24 per group of up to 128 emitted faces |
| Camera order and merge scratch | 32 per translucent face during sorting |
| Retained completed camera order | 8 per translucent face |

`WorldMesh.packedBytes()` continues to report only vertices/indices; `translucentBytes()` reports the new CPU metadata. `faceBatchBytes()` separately reports prepared face bounds. `Usage.outputBytes()` includes all three, and scene planning, cache reservations, source leases, picking/worker admission and GPU source journals inherit the complete retained CPU charge. Empty metadata arrays add no primitive payload; Java array/object overhead remains outside this accounting.

Existing section/scene/cache/shared byte ceilings and face limits are unchanged. Metadata is charged against section mesh and combined working allowances before allocation, so heavily translucent scenes can reach an existing limit sooner. Failed planning or emission publishes a complete diagnostic result rather than truncated geometry.

GPU vertex/index layouts are unchanged. `GlUpload` derives its transfer/storage total from the original packed face size plus existing material/lighting words, markers and atlas pixels. CPU centers are retained through source ownership but are not uploaded or counted as GPU storage; upload completion still requires exact transferred-byte coverage.

## Worker publication and ownership

The sorting worker now catches preparation-to-completion publication failures as well as preparation failures, reports a failed current revision and continues processing later requests after normal cleanup. Completion allocation failure closes the newly retained hold; superseded revisions never replace newer status. The drain loop drops its job reference before lowering scratch admission to retained output.

Replacement continues to admit the new request before releasing prior scene owners, using conservative overlap. Scene-ticket cleanup can affect another owner's accounting, so sorting does not consume idle holds for replacement credit. A failed source release does not falsely release its sort admission. Taken completions keep their independent source lease/hold through caller disposal or GPU handoff. No GL cleanup runs on this worker.

## Deferred verification

At renderer completion, compare every emitted index/center and resulting complete order against the former exhaustive calculation. Include mixed alpha classes, all/zero translucent faces, hidden directions, native offsets and fluid/model placement, reversed winding, distant positive/negative anchors, exact depth ties and changed cameras. Check identical provenance and vertex/index buffers, section checks for opaque-only scenes, cancellation through emission/depth/merge phases and absent partial results.

Exercise exact metadata-byte boundary admission in section/scene/cache/shared budgets, cached reuse and old pinned scenes. Confirm unlit/lit GPU transfer/storage totals exclude centers while source journals retain them. Inject preparation/status/hold/completion allocation failures, stale replacement, take/close before unwind and shutdown; verify later jobs progress and final charges/leases are correct. Profile opaque-heavy and translucent-heavy camera motion and compare real-driver/native pixels before integration or preview enablement.
