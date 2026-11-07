# World solid-face range rejection

Renderer implementation, 2026-11-04. Opaque/cutout drawing now checks prepared bounds inside visible world sections and coalesces surviving original index spans. Background picking-index construction copies those same prepared face bounds. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Runtime, concurrency, native and real-driver comparisons remain deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Prepared bounds and source identity

`WorldMesh.BATCH_FACES` is 128. A section's emitted faces are partitioned into contiguous original-order groups of at most that size, including a final partial group. Each group retains six section-relative floats: minimum and maximum XYZ. Empty/marker-only sections require no face-bound primitive payload.

`WorldMesher` accumulates the bounds while emitting actual float positions, after native visibility, model/fluid placement and float rounding. Every quad contributes all four vertices. Overhangs, rotated geometry and supplied native layers are included without assuming unit-cube shapes. Groups may cross cell, prototype, atlas-page and alpha-class boundaries; these broader bounds can admit extra work but cannot substitute another face or material.

The arrays remain private. `faceBatchCount()`, `faceBatchMinimum(batch, axis)`, `faceBatchMaximum(batch, axis)` and `faceBatchBytes()` expose individual immutable values and accounting. Group `b` covers original faces from `b * BATCH_FACES` up to the smaller of the next group boundary and the section face count. Native state/biome identity, original geometry/index/UV order, cell/prototype provenance and transparent face keys remain intact.

## Solid submission

Whole-section frustum/clip rejection remains the first stage. Within each visible section, every original opaque/cutout material range is intersected with these face groups. A group is rejected only when its complete bounds miss the frustum or active geometric clip. Bounds use the same float section-offset addition as the geometry shader; frustum planes come from the submitted matrix and retain the existing outward numerical allowance. Clip tests expand by four float ULPs at the translated bound magnitude plus 1e-6.

Adjacent surviving intersections within the same material range are submitted as one contiguous original index span. No index buffer, texture, VAO, tint or lighting data is rebuilt. When every intersection is visible, each material range retains its previous single draw call. Rejected gaps can split one range into multiple draws: fewer submitted triangles is not a guarantee of fewer calls or lower elapsed time.

The later [shared group path](TRANSLUCENT-CULLING.md) also filters the original translucent order through these same bounds; diagnostic markers retain section rejection. Mixed-alpha groups can widen solid bounds conservatively; they do not change pass selection, blend/depth state, atlas playback or transparent ordering. Fragment clipping still decides actual boundary pixels, with no invented caps or geometry removal from the native source.

## Counts and comparison mode

`GlRenderer.FrameStats` adds the following fields, which also travel through completed world-frame/readback receipts:

| Counter | Meaning |
| --- | --- |
| `solidRangeTests` | Opaque/cutout material-range/group intersections checked inside visible sections |
| `culledSolidRanges` | Those intersections rejected before index submission |
| `culledSolidFaces` | Original faces skipped by those rejected intersections |

A group spanning several material ranges can be tested more than once. Face counts refer only to the relevant intersection, so a face is not counted twice. Whole-section rejection contributes to the existing section counters, not these new counters. Existing `solidDrawCalls` and `triangleIndices` count actual submissions after rejection. The previous ten-argument stats constructor remains available and supplies zero for the new counters.

The low-level `renderFrame(scene, atlas, plan, target, camera, clip, false)` overload disables only the new solid-group rejection and reports zero new counters. It keeps whole-section rejection, fragment clipping, materials, transparent order and markers. Existing rendering overloads enable the new path. This provides a later differential comparison route without changing source meshes or rebuilding GPU resources.

## Admission and picking preparation

Each nonempty face group adds exactly 24 retained CPU primitive bytes. Count-only section planning adds `24 * ceil(faces / 128)` to packed geometry and translucent metadata before any section output allocation. `Usage.outputBytes()` and scene/cache/shared/source journals include this payload; mesh publication validates exact coverage and total bytes. Existing byte ceilings and preview defaults remain unchanged, so a request at a former boundary can now report an explicit limit. Java array/object overhead retains its existing external accounting boundary.

GPU storage/transfer totals continue to cover actual vertex/index/material/lighting, marker and atlas data. Face bounds remain CPU source metadata; the draw loop adds no per-group visibility array, journal or GL resource. Completed frames retain their existing source ownership and immutable pixels through replacement.

`WorldPickIndex.BATCH_PRIMITIVES` uses the same 128-face group size. Index preparation copies six prepared floats per face group instead of scanning every vertex again. Its existing flattened bound/section-offset arrays, byte limits and independent source lease remain in place; source metadata and index copies are both charged. Marker-bound construction, original primitive query order, ray/clip/alpha policy and detached hit identity remain intact.

## Deferred validation

At renderer completion, compare prepared bounds against an independent exhaustive emitted-vertex scan for empty, marker-only, 1/127/128/129-face and maximum sections, hidden faces, native offsets, fluid/model layers, overhangs, reversed winding and distant/negative anchors. Verify exact count/output/cache/shared admission at group boundaries, cancellation/failure publication, cached reuse and old pinned-source ownership.

Compare enabled/disabled pixel output and original submission spans on real Windows GL contexts. Cover frustum/near-plane crossings, clip planes through vertices and group bounds, extreme relative coordinates, all-visible/all-rejected/alternating groups, material boundaries inside groups, multiple atlas pages, cutout holes and mixed transparent/marker scenes. Confirm contiguous-run coalescing, exact counter meanings, unchanged upload byte completion, native picking equivalence and old-frame/readback receipts. Profile CPU bound checks, draw-call splitting and triangle reduction separately before claiming a performance gain or enabling supported previews. No renderer-stage tests ran for this batch.

[Shared translucent decisions and admitted draw scratch](TRANSLUCENT-CULLING.md) now cache each original group once across material passes, retain global sorted order and provide unique-test/transparent-cull counters plus an immutable differential frame mode. Full/dependency-free compilation passed; all 90 prepared groups remain unexecuted.

[Hierarchical world draw preparation](HIERARCHICAL-WORLD-CULLING.md) now adds admitted internal source bounds, whole-interval classification and explicit hierarchy/linear/none modes. The original leaf bounds and index order remain intact. See that contract for current byte formulas, work counters and deferred validation status.
