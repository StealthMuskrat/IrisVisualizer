# Geometric world clipping

`WorldClip` adds an optional closed box for inspecting a captured world. The GPU, CPU picking and immutable completed-frame receipts use the same world-space bounds. Clipping changes display scope only: original full-height native indices, visibility masks, meshes, atlas data and generation requests remain intact. Unsupported previews remain disabled.

## Bounds and rendering

`WorldClip.NONE` keeps the full geometric display. `new WorldClip(extent)` uses the extent's minimum and maximum boundary planes, inclusive on both sides. The extent describes a box; no cell array, copied world or reduced generation request is created. Minimum thickness follows the existing positive integer extent dimensions. Bounds may lie partly or entirely outside the captured world.

Coordinates are relative to exact integer anchors. Subtraction happens before conversion to double, preserving small differences at distant absolute coordinates. An overflowing anchor difference represents a plane at the corresponding infinity; finite nearby points cannot falsely enter that distant box.

The geometry and marker shaders discard fragments outside the box. Opaque, cutout and translucent passes use identical clipping, with the existing depth, alpha, tint and lighting behavior. Whole sections are additionally rejected against the clip bounds using rounded float positions and an outward ULP margin. Frame section-culling counts combine frustum and geometric rejection; draw/instance/index counters still report submitted work, not surviving pixels.

GPU planes beyond +/-2^21 relative units saturate to that range. Every admitted drawable section offset is within +/-2^20 and its local vertices stay within the existing bounded section/prototype space, so saturation cannot change membership of a renderable point. It avoids infinite uniforms. Actual boundary precision and interpolation still require Windows GPU comparison.

Clipping removes fragments from existing geometry. It does not generate cut-plane caps, reveal faces removed by the original native visibility mask, change back-face culling or create a replacement cross-section model. Native offsets may put geometry from an adjacent cell inside the box; a surface coincident with a boundary is retained. Its original cell/state/biome provenance remains authoritative.

## View and frame ownership

`GlWorldView.clip(clip)` advances only the view revision, invalidates untaken frame output and requests a redraw. Equal bounds are a no-op. It does not regenerate voxels, rebuild meshes, upload geometry or restart a camera sort. The complete existing transparent order remains valid for the clipped subset; a scene with translucent geometry still requires that full camera-bound order, even if the box hides every translucent fragment. Intersection/OIT limitations remain unchanged.

Clipping persists as a world-view display preference across source replacement until the host changes it. The host can use `WorldClip.NONE` when switching dimensions or clearing a cutaway. Frame ownership and failure/retry rules remain unchanged; already transferred caller-owned images preserve their original pixels and clipping receipt.

The explicit APIs are:

| API | Clip ownership |
| --- | --- |
| `GlRenderer.renderFrame(..., camera, clip)` | Borrows immutable clipping for the draw |
| `GlFrame.render(..., width, height, clip, obsolete)` | Captures clipping in the completed image receipt |
| `GlFrameQueue.Request(..., width, height, clip)` | Uses the requested clip for that frame revision |
| `GlFrame.Info.clip()` | Detached immutable metadata, also retained by world-image readback |
| `GlWorldView.clip(clip)` | Context-owned display preference and revision change |

Existing draw/frame/request/receipt constructors and overloads default to `WorldClip.NONE`. No host clipping capability or external GL object is modified; new uniforms belong to the renderer's own programs and existing host-state restoration still applies.

## Picking the displayed scope

`WorldPickIndex.Options` now carries a clip, with its existing constructor defaulting to no clipping. `options.withClip(clip)` preserves node/face/marker budgets, alpha mode and back-face policy. `frame.info().pickOptions(options)` applies that completed image's captured clip; combine it with the frame's ray and exact raster while retaining the frame/index owners required by the existing picking contract.

Query node and marker boxes intersect the clip before ray traversal. Triangle hits outside the box are rejected before alpha inspection or nearest-hit adoption. A clipped nearer triangle cannot hide a farther eligible hit. Query ceilings still bound visited/tested source data, and budget failure still publishes no partial nearest hit. `WorldPick.clip()` records the requested scope on successful, empty and failed query receipts without retaining scene/index/image data.

Marker picking retains its unit-box approximation, now intersected with the clip. This can select a clipped marker volume where a wire-line pixel is absent; it does not establish exact marker-line or transparent pixel parity. CPU double ray tests and GPU float interpolation may also differ at boundaries. Host world/view/index/revision checks continue to govern adoption.

## Deferred stage validation

All 103 independent CPU/OpenGL sources compile with Java 25 and LWJGL 3.4.1 (74 CPU, 29 GL). Twelve native adapters remain syntax-parsed/source-manifest checked only, pending genuine pinned Windows API compilation/execution. No renderer tests, clip/picking execution, GL context, shader compilation or pixel comparisons ran for this batch.

At renderer completion, cover no clipping, one-block slices, every inclusive boundary, outside/disjoint boxes, negative and distant anchors, overflowing differences, offset geometry crossing bounds and absence of generated caps. Verify whole-section conservative rejection and partial-fragment clipping across opaque/cutout/translucent/marker passes, transparent order reuse, tiny visible subsets, statistics and host-state restoration.

Check near-clipped/far-eligible triangle hits, clipped node/marker boxes, rays starting inside/outside/on planes, parallel rays, alpha tests, budgets and detached clip receipts. Exercise rapid clip/camera/clock/world changes, pending sorting, stale frame tokens, same-value no-ops, constructor compatibility, retained old frames and readback receipts. Compare real Windows shader/picking/readback pixels at float boundary cases before enabling any supported preview. Native fidelity, complete renderer-stage testing and final editor integration remain pending.

[Scalar clipping](SCALAR-CLIPPING.md) now applies the same world-coordinate display-box contract to 3D noise views, carrying its exact scope through shader/range rejection, view/frame/readback receipts and original-value picking. Earlier scalar APIs retain `NONE` defaults. Full captured volumes and instance buffers remain unchanged.

## Camera fitting within the clip

[Explicit clipped camera fitting](../renderer-gl/CAMERA-FIT.md) now frames the intersection of prepared world/scalar bounds and the current clip through `fitClipped`. Full-source `fit` remains available and does not change clip settings. Empty sources retain their configured-extent fallback, while a non-intersecting scope reports STATE before connected-view mutation. Conservative AABB fitting performs no voxel/triangle filtering, native generation or cap construction; existing camera precision and current-upload guards remain in force.
