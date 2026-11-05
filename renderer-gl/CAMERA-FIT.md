# Full-source and clipped camera fitting

Renderer implementation, 2026-11-04. World and scalar views now expose explicit camera fitting within their current geometric clip. Full-source fit remains available, including the existing empty-source fallback. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Code inspection and compilation only: camera/runtime, failure-injection, native and real-driver checks remain deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Explicit display scope

| Operation | Fitting bounds |
| --- | --- |
| `GlOrbitCamera.fit(scene, aspect)` | Complete emitted world geometry/marker bounds |
| `GlOrbitCamera.fit(scene, aspect, clip)` | Union of section-bound intersections with the supplied clip |
| `GlOrbitCamera.fit(surface, aspect)` | Complete scalar display-surface bounds |
| `GlOrbitCamera.fit(surface, aspect, clip)` | Intersection of scalar surface bounds with the supplied clip |
| World/scalar view `fit(width, height)` | Full-source fit, preserving its separately configured clip |
| World/scalar view `fitClipped(width, height)` | Fit using that view's current clip |

`GlWorldView.fit` supplies the world counterpart to the existing scalar convenience method. `fitClipped` is explicit opt-in. `WorldClip.NONE` selects the same full-source calculation as the original two-argument orbit helpers. Fit does not enable, disable or alter the clip itself, sampling range, scalar inclusion/color policy or native generation configuration.

The orbit helpers borrow successful CPU inputs and return detached camera state. They perform no GL work and retain no source graph. Keep the corresponding CPU owner live during the call. The connected view helpers run on the original context, require a complete adopted current upload and validate target dimensions before calculating a camera. A cleanup-retained old scene cannot satisfy these requirements.

## Conservative bounds and empty inputs

World fitting visits each nonempty section's actual emitted geometry/marker bounds. It subtracts exact integer section/source anchors before converting relative offsets to doubles, preserves the existing relative precision check, intersects each translated section AABB with the clip and unions the remaining intersections. Overhangs and native model/fluid offsets already present in section bounds remain covered. Sections outside the clip contribute no fitting extent.

The result is conservative: an AABB can contain empty space between primitives. Fit does not scan all triangles, determine texture alpha, reproduce raster visibility or guarantee a visible pixel at every part of the fitted box. Tint, material passes, transparent ordering and diagnostic-marker identity stay with the original geometry.

If a world has no drawable geometry or markers at all, it uses the complete captured extent as the fitting source, intersected with the clip. If drawable section bounds exist but none intersect the scope, fit reports STATE rather than silently reverting to the full world. Closed boundary contact is retained, including intersections with a zero-sized axis.

Scalar fitting uses the surface's prepared relative bounds, which already reflect its selected native samples and display-bin spacing. An empty surface uses the complete display-bin volume extent, including full configured height. The chosen box is intersected with the same exact-anchor-relative clip. No source resampling, missing-value substitution or clipped cap geometry is introduced.

## Camera policy and failure behavior

The original sphere-fitting policy is shared by both routes: 60-degree vertical FOV, 45-degree yaw, 30-degree pitch, the tighter horizontal/vertical view angle, 5% distance padding, minimum half-block radius and the existing near/far rules. World anchors remain exact integers. Limits on camera relative coordinates, clipping distance, aspect and device target dimensions remain unchanged; clipping cannot bypass an out-of-range source section.

A non-intersecting scope reports `GlFailure.Code.STATE` before a connected view changes its camera, viewport, display revision or untaken image. Invalid dimensions/aspect, an unadopted source and precision/clipping-limit failures likewise fail during preflight. Once a valid fit enters the normal `view` update, existing frame-retirement and failure/retry behavior applies.

Successful fitting follows existing display revisions and no-op handling. A changed pose invalidates old camera ordering and requests a new asynchronous translucent order as needed; unchanged poses can reuse it. Complete geometry and source uploads remain reusable. Completed frames and readback retain their captured camera/clip, so caller-held old images and pointer queries continue to use their own receipts.

## Work and deferred verification

World fitting uses one section-metadata traversal and one small bound accumulator; scalar fitting uses one prepared box. No face/voxel array, picking index, GPU object, worker, primitive payload budget or preview default is added. This bounds work by source metadata but makes no elapsed-time or measured performance claim.

At renderer completion, compare original full-source helpers with explicit NONE results and verify identical poses. Cover odd/full-height extents, negative and distant long anchors, overhangs, model/fluid offsets, marker-only/empty worlds and empty scalar surfaces. Check disjoint, enclosing, partial, boundary-touching and far-overflow clip boxes, section unions versus union-then-clip, scalar fractional spacing and conservative empty gaps.

Validate sphere/FOV/aspect containment, minimum-radius/near/far policy, relative-coordinate limits and failed fits preserving view tokens/camera/viewport/frames and source diagnostics. Exercise complete versus pending/failed/cleanup-retained source guards, no-op fits, successful pose changes, transparent order reuse/replacement and existing retirement failures after view admission. On real Windows GL, compare frame/picking/readback camera/clip receipts and visible geometry after full/clipped fits while keeping native arrays, complete uploaded geometry and caller-held images intact. Runtime/native/GPU validation and final editor integration remain pending; no renderer-stage tests ran for this batch.
