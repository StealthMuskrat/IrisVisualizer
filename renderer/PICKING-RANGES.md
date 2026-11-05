# World picking range culling

Renderer implementation, 2026-11-04. All 128 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (87 CPU, 41 GL). Source inspection and compilation only: no picking/index/query/worker execution, renderer tests, native comparison, GL context, Windows visual checks or workflows ran. Renderer-stage execution remains deferred until implementation is complete. Unsupported previews remain disabled.

## Bounded original-order ranges

`WorldPickIndex` now indexes contiguous groups of at most 128 faces and, separately, 128 diagnostic markers inside each section. The existing section hierarchy remains the first rejection stage. A reached section then checks these smaller bounds against the query ray, near/far interval, geometric clip and current nearest distance before testing individual primitives.

Face bounds come from all four emitted vertices, after actual world-mesh placement. [World mesh emission](../renderer-gl/WORLD-RANGES.md) now prepares those same 128-face bounds; picking-index construction copies their six floats per group instead of scanning every source vertex again. Both the retained source metadata and flattened index copies are admitted under their existing byte ceilings. They include native offsets, overhangs, non-cube models and fluid layers without assuming unit-cube geometry or using block names. Marker bounds include their original diagnostic unit-cell boxes. World origins are subtracted exactly before storing section-relative float bounds. No native state/biome IDs, meshes, UVs, atlas pages, visual assignments or source buffers are changed.

Ranges preserve section and primitive order. Faces still use the original two indexed triangles, barycentric UVs, back-face policy, displayed atlas alpha thresholds and clipping. Markers still use their existing clipped unit-box diagnostic approximation. Equal-distance ordering remains section, geometry-before-marker, then original face/marker index. Nearest results retain the same native position/state/biome/source and original indices; the range index never appears as a substitute hit identity.

Bounds are expanded at query time by 1e-6 world units plus eight double ULPs at the relative-coordinate magnitude. The fixed margin exceeds the barycentric edge tolerance over the admitted +/-128 section geometry, while the ULP term allows slab arithmetic rounding. Uncertain boundary groups remain candidates; actual primitive tests decide hits. This is a conservative numerical policy, not proof of bitwise equivalence for ill-conditioned triangles or GPU edge pixels. Near-parallel, distant-anchor and edge/corner cases remain in the deferred comparison matrix.

## Options and work receipts

`Options.batchCulling` defaults to true through both prior constructors. `withBatchCulling(false)` restores the exhaustive face/marker loop inside each reached section; it preserves the existing section hierarchy and does not rebuild/free index arrays. `withClip` preserves the selected culling mode, including through completed-frame option helpers.

`WorldPick.Stats.batches()` counts tested face and marker ranges together. `culledBatches()` counts ranges skipped before individual primitive work, including missed, clipped and behind-nearest groups. Existing node/section/face/marker/alpha counters retain their meanings and budget-attempt behavior; the earlier five-argument Stats constructor supplies zero range counters. Unculled queries report zero range counters.

Face/marker allowances now apply only to reached individual primitives. A query can succeed within an allowance that the exhaustive path exhausts, because rejected groups require no individual tests. Any exhausted node/face/marker budget still reports LIMIT with no partial nearest hit. For result comparisons, give both modes sufficient complete-scene allowances rather than expecting equal work counts or identical failures under smaller allowances.

Cancellation checks run at each range and through existing primitive loops. No extra source index, list or whole-volume scratch is allocated per query. `WorldPickWorker` uses the same path and includes the new retained arrays through its existing complete request-payload estimate. Results/status remain detached from index and scene arrays.

## Admission and lifetime

Preparation counts every drawable section and both range families before array allocation. [Prepared complete-scene totals](PICKING-SIZES.md) now size background-worker admission without a synchronous count scan; actual preparation recounts and validates them before allocating. Valid requests reserve actual retained/build primitive demand rather than the full configured index ceiling. The existing 16 MiB index and 512 MiB scene-plus-index preparation ceilings include:

| Payload | Primitive bytes |
| --- | --- |
| Existing section hierarchy | 60 per node; 2N-1 nodes for N > 0 drawable sections |
| Face/marker range bounds | 24 per range, six section-relative floats |
| Section range offsets | 4 × (section count + 1) for each nonempty range family |
| Temporary drawable-section IDs and build cursor | 4N + 4 for a nonempty hierarchy |

A wholly empty scene needs no range/offset arrays. Marker-only scenes need no face-range arrays, and face-only scenes need no marker-range arrays. The conservative simultaneous hard-scene upper bound is 12,361,680 preparation bytes, including 65,536 drawable sections, up to 81,408 face ranges and 73,216 marker ranges. Actual arrays are counted exactly. Lower index/combined limits can reject requests that previously fit; no ceiling, area, height or generation semantics is changed to accommodate them.

Shared admission reserves the complete source and all prospective arrays before construction. Successful preparation shrinks away temporary IDs/cursor. `retainedBytes` includes hierarchy, both range bounds and offsets; `admittedBytes` continues to include the retained source under a coordinator. Cancellation/failure consumes and closes the supplied CPU scene owner without publishing a partial index. Close prevents new queries and releases arrays/source only after active queries unwind, retaining existing cache and shared-budget charges throughout.

Java object/view/list overhead, recursion frames, source cache ownership and unrelated native/GPU work retain their existing external accounting boundaries. This change supplies no CPU timing, total-process memory or native/GPU fidelity claim.

## Deferred renderer-stage checks

Compare culled and exhaustive queries with complete allowances across empty/one-range/129-primitive/multiple-range sections, face-only/marker-only/mixed scenes and maximum scene shapes. Include tiny/planar/rotated/degenerate quads, reversed endpoints, overhangs, model/fluid offsets, original-order ties across range/section boundaries, inside boxes, near/far equality and distant/negative anchors. Check parallel/near-parallel rays, barycentric tolerance edges, clip planes through bounds, clipped nearest faces and another candidate behind them.

Exercise current animated alpha holes/interpolation, both back-face policies, GEOMETRY/displayed alpha modes, exact source hit metadata and all counters. Verify sufficient-budget hit/miss equivalence independently of differing work counts; separately exhaust every budget after skipped/admitted groups and require no partial hit. Confirm old constructors and `withClip` retain the intended defaults/mode.

Check exact and one-over array/combined/shared bounds, upper-bound calculations, all failure/cancellation construction points, count/emission consistency, marker coordinate conversion, source closure during active queries, pending worker replacement and final zero charge. Measure actual work/time on representative Windows scenes before claiming a performance improvement. Native visual fidelity, real GPU pixel boundaries, final editor routing and renderer-stage runtime tests remain pending.
