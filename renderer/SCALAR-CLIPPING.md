# Scalar display clipping and frame-bound inspection

Renderer implementation, 2026-11-04. All 126 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (85 CPU, 41 GL). Sixteen native adapters pass syntax parsing only; genuine pinned Windows native dependency compilation remains pending. No scalar query/mesher execution, GL context, shader compilation, pixel comparison, renderer tests or workflows ran. Unsupported previews remain disabled.

## Display scope

Scalar views now accept the existing `WorldClip` contract: an optional closed box in exact integer world coordinates. Its coordinates describe display geometry, independently of scalar grid spacing, inclusion thresholds and configured generation height. A box may intersect only part of a display bin or a merged rectangle. Source values/presence, original sample positions, selection styles, full volume, surface faces and GPU instances remain unchanged.

Clipping discards portions of existing surface faces. It adds no cut-plane caps or interior faces, and does not remesh the thresholded volume. A slice through the interior of a solid selected region can therefore show background where no original boundary face exists. To change which samples contribute surfaces, change the explicit inclusion style through normal mesh preparation instead. Camera fitting continues to use the complete surface bounds.

## GPU drawing and bounded rejection

`GlNoiseRenderer.render(scene, target, camera, clip)` sends camera-relative vertex positions to the scalar fragment shader. Fragments outside any closed clip plane are discarded. The previous three-argument overload uses `WorldClip.NONE`. Existing simple lighting, depth/back-face policy, colors, instance storage and shader expansion remain intact.

Clip planes subtract exact world anchors before conversion. Far/overflowed relative planes saturate at plus/minus 2^22 for shader uniforms, strictly beyond the supported scalar local-span plus camera-offset range (at most 2^21). Within the renderable range, integer clip planes remain exactly representable as floats. This saturation avoids nonfinite uniforms while preserving the side of every supported rendered coordinate. It does not alter source coordinates or relax precision guards.

The existing [scalar range culling](SCALAR-CULLING.md) also rejects complete ranges outside the clip before submission. That test uses shader-equivalent float bounds expanded by an outward float-ULP margin; uncertain boundary cases stay included and the fragment shader applies the actual plane test. Frustum and clip rejection share existing counters. Consecutive accepted ranges still coalesce into one instanced draw. Partially clipped ranges count their full submitted faces, not surviving pixels.

Camera/precision/clip-bound preparation occurs before target invalidation. Empty or fully clipped views still produce complete background images after successful clear/restoration. The original state guard restores host state on success/failure; clipping adds only uniforms and one position varying. It introduces no new source/mesh/GPU array, upload, resource journal, CPU worker reservation or device ceiling.

## View revisions and image ownership

`GlNoiseView.clip()` returns the current scope; `clip(WorldClip)` updates it. Equal scopes are a no-op. A change advances only the view revision, drops the view's untaken completed frame and requests a redraw of the existing scene. It does not resubmit native sampling, surface preparation or texture/instance upload. A clip can also be configured before data arrives; successful current upload uses the latest scope.

Source/CPU/upload failures remain failures when no complete scene exists. Clip changes preserve those diagnostics and their error code/reason, matching the scalar camera-change contract. A complete-scene draw failure may be retried by a changed clip or explicit redraw. Invalidation and new data submission retain the chosen display scope, while their normal data/view revision rules reject obsolete work. Closing/replacing the view does not change caller-transferred frames.

`GlNoiseFrame.render` has a clip-aware overload. Its immutable `Info.clip` records the exact box used for that image, alongside the original source/grid/style, camera and submission statistics. Older render/receipt constructors use `NONE`. The readback pipeline automatically preserves this same scalar receipt; no reconstruction from the current view occurs. Frame targets and retained source owners keep their existing independent ownership/retirement rules.

## Matching CPU inspection

`NoisePick.Options(maximumFaces, clip)` supplies the query scope. The existing integer-allowance query and older result constructors retain the unbounded default. Query results now also record their clip on success, miss and structured failure.

For a completed image, capture the live frame's immutable surface and use `info.pickOptions(new NoisePick.Options(allowance))` with `info.ray(x, y)`. The helper replaces an unrelated caller clip with the exact image's clip while preserving the face allowance. Keep that frame owner live during background inspection, as before; no large query is implicitly executed on the GL thread.

Queries convert clip planes relative to the exact grid origin, intersect conservative range slabs with that box and reject face hits outside its closed planes. The full-surface face allowance is still checked before searching. Cancellation, tested/culled range counters, near/far bounds, front-face policy and original sample/direction tie order remain in place. Hits return original samples and values, including when a plane cuts a merged rectangle partway through one displayed sample. No cap hit or substitute sample is fabricated.

CPU intersection uses double arithmetic while rasterization uses floats and interpolated fragment positions. Edge/corner pixel agreement remains a deferred real-driver check, not a bitwise parity claim. Scope is geometric: it is separate from shader frustum visibility and does not rewrite generated block/biome data or native noise.

## Deferred stage verification

At renderer completion, compare unclipped/default overloads with the prior reference and exercise boxes inside, outside, covering and touching all six surface sides. Include fractional bin spacing, merged rectangles crossing planes, planar batches, negative/distant anchors, full configured Y, far signed-long clip extents and empty/fully clipped output. Compare whole-range rejection with fragment-only clipping, and verify exact boundary inclusion, colors/depth/winding, face/run statistics and unchanged upload/source/mesh payloads.

For matching image/query pairs, cover parallel rays, near/far/edge/corner ties, clipped nearest faces with another face behind, sample-edge ownership and partial greedy rectangles. Verify clip receipts on success/miss/budget failure, image option helpers, prior constructors and stale source/view tokens. Exercise clip changes during pending CPU work/upload, source-failure preservation, draw retries, equal-scope no-ops, independent old frames/readback and close/restoration failures. Native sampling, visible Windows QA, renderer-stage runtime tests and final editor integration remain pending.
