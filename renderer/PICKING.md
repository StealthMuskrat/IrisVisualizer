# Native inspection and camera navigation

Renderer implementation, 2026-11-03. All 76 Java sources compile with Java 25 and pinned LWJGL 3.4.1: 55 CPU and 21 OpenGL sources. No picking, shared-lease/concurrency, camera, pixel comparison, GL or interactive Windows tests have run. Renderer tests remain deferred until implementation is complete; unsupported previews remain disabled.

## Shared ownership of an exact scene

`WorldScene.Result.retain()` returns an independent owner of the same immutable CPU scene. Retaining after close fails. Each shared ticket bundle allows at most 65,536 live result owners. A new owner is allocated before its reference count increases, so failed owner allocation cannot leak a reference. Each wrapper's close is idempotent and clears its shared-scene reference. Cache tickets remain pinned until the final shared owner closes.

Retaining does not duplicate meshes or add one cache ticket per section again. Cache statistics continue to count actual section tickets, while their shared bundle remains live. Closing a GPU scene does not invalidate another retained CPU owner. Closing a cache keeps shared tickets charged as before; each pinned epoch remains charged when a different native epoch is admitted within the shared aggregate budget. Insufficient combined capacity still blocks replacement. An escaped bare scene reference is still outside the ownership contract.

`GlScene.retainCpuScene()` checks the original context/device and returns this independent CPU owner. The returned owner can be transferred to a worker without calling GL there. Close it explicitly or transfer it to `WorldPickIndex.prepare`. Do not pass a result owner already consumed by GPU upload or another index; obtain a fresh retained owner first. This does not change the existing duplicate-device-owner protection in upload.

## Complete section index and bounded queries

`WorldPickIndex.prepare(freshCpuOwner, cancelled)` consumes that owner on every outcome after nonnull checks. Run index preparation off the UI thread. It admits a balanced hierarchy over every nonempty section's actual emitted geometry/marker AABB, preserving regular scene section order. The hierarchy has one leaf per drawable section, not one leaf per nominal voxel or face. Empty sections remain in the native scene but need no drawable index leaf.

Build admission checks all index arrays and temporary section IDs before allocating them. Each node uses six doubles and three integers (60 primitive bytes); temporary admitted section IDs cost four bytes per leaf. For N drawable sections, there are `2*N-1` nodes, or zero for an empty scene. The default/hard index preparation ceiling is 16 MiB and the combined scene/index CPU ceiling is 512 MiB. The hierarchy can admit the complete maximum 65,536-section scene when its combined budget fits. It never omits a section to fit.

Bounds use exact checked section-origin subtraction from the native extent anchor before converting to relative doubles. Coordinate differences beyond 1,048,576 fail explicitly, matching the backend's relative precision policy. Build failure/cancellation closes the transferred owner and publishes no partial index. A failed index retains only its source receipt/problems; it cannot pin a partial scene.

`WorldRay` stores an exact world anchor, bounded relative origin, normalized direction and finite minimum/maximum distances. `index.query(ray, cancelled)` uses default options and the scene's base raster. The full overload accepts explicit query limits, back-face policy and a supplied displayed atlas raster. It validates that raster's successful exact layout identity. Keep an animated frame/raster owner alive while a query using it is pending or active; the picker does not create a new uncharged GPU frame owner.

Queries visit nearer child bounds first and prune nodes beyond the best known distance. Each reached section tests its actual indexed triangles using double arithmetic and barycentric UV interpolation. Candidate hits remain scalar query state until the final receipt is built, avoiding per-candidate inspection-object allocation. Source indices and original face order remain unchanged. By default back faces are rejected to follow the backend's culling convention. An optional two-sided geometry query can inspect backs without changing rendering. Degenerate triangles and unsupported coordinate differences are handled without inventing a unit-cube replacement.

| Query resource | Default | Hard bound |
| --- | --- | --- |
| Hierarchy node tests | 131,071 | Same |
| Emitted face tests, two triangles per face | 262,144 | 2,097,152 |
| Diagnostic marker box tests | 65,536 | 1,048,576 |
| Concurrent queries on one index | 64 | Same |

Limits can be lowered through `Options`. Exhausting a test budget produces a structured LIMIT result with **no hit**, even if a candidate was found earlier: it would not yet be a certified nearest candidate. Counters report actual tested nodes/sections/faces/markers and rejected alpha candidates. Alpha rejection is counted per tested triangle candidate, not unique native block. Cancellation propagates separately and checks through hierarchy and bounded face/marker groups. There is no hard latency guarantee for a maximum-size query; interactive consumers use the background worker.

## Texture alpha and placeholder inspection

The default displayed-base-level policy follows the current nearest/clamp-to-edge RGBA8 backend. Opaque faces accept geometric hits. Cutout candidates reject alpha below 128; translucent candidates reject zero alpha. Atlas UVs select the corresponding nearest base-level texel. Pass the exact displayed animation raster to avoid inspecting a different frame's transparent holes. Geometry-only mode ignores texture alpha. This supports the backend's current simple material path, not filtered/mipmapped textures, native OIT or a dominant-contribution interpretation of transparent compositing.

Diagnostic markers are selectable through their unit-cell boxes. Their hit is a volume diagnostic rather than a wire-line pixel test or a substituted block. A hit retains exact native position, state ID/text/properties and biome identity, plus section/face or marker index, ray distance and local hit coordinates. Geometry surfaces additionally retain source material/tint/emission metadata, atlas UV and geometric normal. Marker hits retain the original visual problems.

Equal-distance hits use section order, geometry-before-marker within a section, then face/marker order for deterministic ties. Results contain an index UUID, generation receipt and original ray. They retain no index, mesh, atlas raster or complete native scene graph. A successful miss has no hit/problems; a failure has problems and no partial hit. This is visual mesh inspection, not native collision-shape selection or voxel editing.

Closing an index prevents new queries and cancels active ones cooperatively. It does not wait for a query on the closing/UI thread. The CPU lease and index arrays stay owned until the last in-flight query unwinds; `released()` and `retainedBytes()` expose that deferred lifetime. Closing again is safe. Replacing a scene may therefore temporarily remain blocked by the cache until cancelled picking finishes, preserving accounting rather than dropping a live source charge.

Index bytes and independent CPU leases belong to CPU/cache admission; they are not silently added to `GlDevice`'s private GPU ledger. Application integration must include these external CPU jobs/indices, live atlas rasters, object overhead and other caches in combined admission. These primitive ceilings do not establish total process memory safety.

## Latest-pointer worker

`WorldPickWorker` runs one active query and retains one replaceable pending request. It borrows caller-owned indices/raster inputs and never closes them. `submit` returns a monotonically increasing picking revision; a newer request cancels obsolete work and clears untaken old output. `invalidate()` advances the token immediately after a scene, camera, viewport, pointer or displayed animation change, even before replacement query inputs are prepared.

`take(latestRevision)` transfers the current detached result once. The application must check revision, current index UUID and current view/frame context again before showing its inspection. A matching generation hash or native source receipt alone is insufficient. Failed/closed indices and exhausted query budgets become explicit failed status; later submissions can reuse the worker. Closing cancels without joining from the UI thread. Query payload locals unwind before the executor waits, and completed inspection receipts cannot retain the full old scene.

This worker starts no native generation, rebuilds no mesh, mutates no pack and grants no readiness. It is separate from the one-active-generation-task rule. Actual ImGui input routing, inspection display and application adoption remain integration work.

## Camera rays and orbit controls

`GlCamera` now shares one orthonormal forward/right/up calculation between its view matrix, pointer rays and navigation panning. Exact top/bottom views retain the alternate-up convention. `ray(x, y, width, height)` uses continuous top-left viewport coordinates; an integer pixel-center query explicitly passes `x+0.5, y+0.5`. It rejects coordinates outside the supported 1–4,096 pixel dimensions. Near/far ray distances account for the off-axis forward cosine, so perspective corners use the same clipping planes as rendering rather than a shorter spherical distance cutoff.

The CPU calculation uses doubles while GPU matrix/vertex operations use floats. Exact pixel-edge/rasterization behavior and alpha-texel boundaries still require real-driver comparison; this is not a claim of bit-identical GPU picking.

`GlOrbitCamera` is immutable display-only state. Yaw wraps around a full turn; pitch stays between −89.9 and +89.9 degrees. `orbit` consumes degree deltas. `pan` consumes screen-pixel deltas and viewport height, translating the focus in the camera's right/up plane at focus depth. `dolly` multiplies focus distance and bounds it inside the existing clip interval. These are camera limits, not changes to generation area, world coordinates or data.

`fit(scene, aspect)` frames a sphere around actual emitted geometry and diagnostic marker bounds, including overhangs. Empty scenes use their full configured extent. It never silently shortens dimension height or crops world data to satisfy camera precision/clipping limits. `focus(hit)` moves the view target to a current detached pick point; the application checks its receipt first. `rebase(newAnchor)` preserves world pose using exact integer anchor subtraction before relative coordinate conversion. Pose/view changes require new translucent ordering and picking revisions under the existing contracts. Navigation performs no GL calls and enables no editor preview.

## Deferred renderer-stage verification

At stage completion, verify shared-result retain/close races, all 65,536 owners, closed/failed results, allocation failure before reference increments, final ticket release, cache shutdown and epoch pressure while picking outlives GPU retirement. Cancellation/failure of index preparation must close every transferred owner exactly once, and a query must retain ownership until it fully unwinds after index close.

Compare hierarchy queries with exhaustive triangle/reference intersections for distant/negative anchors, exact section borders, overhangs, empty/hidden/marker-only scenes, starting inside bounds, parallel/near-parallel rays, diagonal edges, degenerate triangles, front/back policy and clipping endpoints. Check every query/working limit, deterministic ties, cancellation and absence of partial nearest hits after budget exhaustion. Verify original native position/state/biome/source metadata and receipt detachment.

Compare opaque/cutout/translucent picking with actual current-frame GPU pixels, including transparent holes, gutter boundaries, zero/partial alpha and animated frames. Placeholder volume behavior must remain clearly diagnostic. Check worker replacement and stale success/failure during query, result delivery, scene/frame change and close; confirm no old index/raster/history accumulates.

Verify camera basis and center/corner rays over FOV/aspect extremes and vertical poles, near/far plane intersection, stable orbit wrap, finite pan/dolly limits, focus and exact-anchor rebase. Frame actual emitted extents and full empty dimensions without hiding unsupported coordinates. Profile the bounded worst-case face scan and interactive latest-query responsiveness on Windows. No renderer tests were run for this batch.

Reference for the current sampling boundary: [Khronos texture parameter contract](https://wikis.khronos.org/opengl/GLAPI/glTexParameter). Native model/collision policies, filtered alpha rules and visible GPU parity remain unverified.
