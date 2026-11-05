# Scalar GPU images, navigation and inspection

Renderer implementation, 2026-11-04. All 119 CPU/OpenGL Java sources compile on Java 25 with LWJGL 3.4.1: 83 CPU and 36 GL. Sixteen native adapters remain syntax-parsed/source-manifest checked only; genuine pinned Windows native compilation is pending. No renderer tests, scalar sampling/query/worker execution, GL context, shader compilation, upload/draw/readback or pixel checks ran. Unsupported previews remain disabled.

This connects the [full-height scalar capture and boundary meshes](../renderer/NOISE-VOLUME.md) to the existing OpenGL device. It renders explicit opaque display bins, independently from Minecraft block geometry, textures, IDs and biomes. It neither evaluates noise nor changes native generation/readiness. The pinned generator's Y-invariant behavior and expression stream's actual 3D evaluation stay in the native producer.

## Compact immutable scene upload

`GlNoiseUpload.begin(device, surface)` borrows a complete immutable `NoiseSurface` and immediately charges its retained sample/mesh payload to a CPU-only device journal. The consuming `RenderInput<NoiseSurface>` overload retains the source through destination admission, requires the same shared coordinator for tracked input, and closes the supplied wrapper on every outcome. Retain a retry owner first if needed.

The admitted instance buffer uses twelve bytes per emitted rectangle, supporting both [greedy and unit surfaces](../renderer/SCALAR-GREEDY.md):

| Word | Encoding |
| --- | --- |
| First unsigned 32-bit word | Original anchor sample index shifted left three bits, plus cardinal direction-enum ordinal 0..5 |
| Second unsigned 32-bit word | Explicit display RGBA8 in `RRGGBBAA` bit order |
| Third unsigned 32-bit word | U/V lengths minus one in two 12-bit fields; zero means one unit in each tangent |

The source sample cap is 16,777,216, so the packed sample/direction word fits without truncation. No second full CPU vertex/color/index array is allocated. Existing staging writes whole twelve-byte instances, at most 64 KiB per transfer action, in native byte order. The existing per-step byte/action/work ceilings and cooperative elapsed target apply. Storage bytes are reported separately. Base RGBA is now [prepared on the CPU](../renderer/SCALAR-COLORS.md); uploads perform direct color lookups. Driver calls and staging still cannot provide a hard elapsed-time guarantee.

The first storage action admits the complete GPU buffer plus one VAO before names/storage. The instance attribute is an integer triple with divisor one. Layout setup follows complete data transfer. Only full face coverage and successful host-binding restoration allow READY/take. Zero-face surfaces need neither buffer nor VAO and still produce a successful scene. Cancellation or failure retires only upload-owned work under existing fence/quarantine accounting.

`GlNoiseScene` is an independently retainable scene owner. Its journal keeps the exact CPU surface and full scalar volume charged until actual retirement. A last-owner close retires its resources; another retained owner stays live. Pending GPU commands, dependencies and failed cleanup preserve existing device charges. Every operation uses the original thread, GLFW context and LWJGL capabilities.

## Drawing and coordinate bounds

`GlNoiseRenderer.create(device)` owns one GLSL 330 program. For each face instance, the vertex shader reconstructs its original X/Z/Y index, expands the matching outward-wound four-corner rectangle by its direction-specific U/V extent using six triangle vertices, and applies the original configured horizontal scale. The whole nonempty surface submits one instanced triangle draw. Empty output clears a complete background image without a draw. Statistics count submitted faces/triangles, not surviving pixels.

Opaque depth-tested faces use back-face culling and fixed simple directional lighting with an ambient floor. Display colors do not claim native biome tint, texture or lightmap parity. Interior faces have already been removed by the CPU display mesh. There is no transparency, interpolated isosurface, volume blending, Minecraft material atlas, extra whole-scene sort or clipping-cap geometry. [Scalar display clipping](../renderer/SCALAR-CLIPPING.md) now discards outside-box fragments and skips complete outside ranges while preserving original data. [Scalar range culling](../renderer/SCALAR-CULLING.md) now rejects bounded original-order face ranges against the camera frustum before instanced drawing. Consecutive visible ranges coalesce, preserving one draw when everything is visible.

The grid origin/minimum Y is an exact integer camera anchor. Integer subtraction precedes float conversion through the existing camera-offset contract. Upload rejects nonfinite/subnormal float scale and horizontal relative spans above 2^20. Drawing additionally rejects spacing smaller than four float ULPs at its conservative camera-relative magnitude, preventing bins collapsing into one coordinate. Y remains one block per sample. The CPU source/height/scale is not changed to satisfy these bounds. Initial camera fitting also rejects scenes beyond existing far/relative-camera limits.

Camera matrix/anchor/spacing validation and host-state capture/normalization precede target invalidation. Drawing uses the existing full state guard, including optional host clip-control normalization and active transform-feedback preflight. A validation failure preserves an existing complete target. A failure after target work starts leaves it incomplete. No successful frame/statistics escapes failed drawing/restoration.

## Completed frames and readback

`GlNoiseFrame.render(renderer, revision, scene, camera, width, height, obsolete)` borrows inputs and owns a fresh private target plus an independently retained scene. Target/frame journals depend on the exact scene and image allocation. Only complete drawing/restoration and the final obsolete gate publish the frame. Failure retires newly owned resources and does not alter an old caller-transferred frame.

The detached `Info` records image UUID, display revision, exact volume UUID, original native resource context/grid, inclusion/color style, camera, dimensions, exact geometric clip and submission counts. It contains no volume/mesh arrays or GL owner. `info.ray(x,y)` uses the original viewport, near/far planes and camera. A top-left image widget must flip the borrowed target texture's V coordinates. Retain an independent frame owner before sharing image lifetime; close owners on the original context.

`GlReadback.begin(noiseFrame)` and `GlReadbackQueue.submit(revision, noiseFrame)` now support these frames using the existing bounded PBO/fence/top-left CPU-copy path. `Pixels.noise()` carries their original detached receipt. Exactly one world/map/noise receipt is required; the original three-argument world/map `Pixels` constructor remains available. No historical source raster is reconstructed or resampled during readback.

## Connected view and revision adoption

`GlNoiseView(renderer, workerBytes)` owns one `NoiseSurfaceWorker`, one upload, current GPU scene and untaken frame. Its CPU worker inherits the device's optional shared memory coordinator. The renderer/device and caller-transferred frames remain borrowed/caller-owned.

| Operation | Effect |
| --- | --- |
| `submit(Request)` | Admit CPU surface preparation, then advance data/view tokens and release obsolete view-owned output |
| `submit(RenderInput<NoiseVolume>, style, limits)` | Consume original admitted native samples after same-coordinator/destination admission |
| `invalidate()` | Immediately reject old CPU/upload/image adoption before replacement native samples arrive |
| `view(camera, width, height)` | Change only display revision and target request; equal configuration is a no-op |
| `fit(width, height)` | Fit actual included-bin bounds; an empty surface uses the full captured extent; requires complete upload |
| `clip(WorldClip)` | Change only geometric display scope and view revision; reuse source/mesh/upload and preserve source failures |
| `redraw()` | Retry/redraw the existing complete GPU scene without native sampling or mesh upload |
| `advance(budget, dataRevision)` | Adopt matching CPU completion, advance at most one bounded upload step, then draw a requested complete image |
| `take(dataRevision, viewRevision)` | Transfer the exact current completed image once |

The host can configure a camera before data arrives, or wait for WAITING_VIEW after upload and call `fit`. Orbit/pan/dolly use the existing immutable `GlOrbitCamera` and feed a new camera to `view`. Threshold changes prepare another display mesh from retained immutable volume input; color-only changes can use [geometry-sharing recolor requests](../renderer/SCALAR-COLORS.md). Neither reruns a native sampler. CPU admission rejection preserves previous tokens/work. Source failures remain visible through subsequent camera/resize changes until resubmission. Display failures with a complete scene can be retried by camera change or `redraw`.

CPU completion admission remains live through device-journal admission before its input closes. This conservatively charges overlapping source/destination accounts. Data/view revisions are separate, and an accepted later request cannot revoke caller-transferred frames. Repeated `advance` on an unchanged delivered view performs no redundant upload/draw. Whole-frame drawing is synchronous, as in world/map rendering. Shutdown cancels CPU work nonblocking, retires view-owned uploads/scene/images with retryable cleanup, and leaves independent frame owners live.

## Bounded original-value inspection

Capture `frame.surface()` on the owning context and retain the frame throughout inspection. Run `NoisePick.query(surface, frame.info().ray(x,y), frame.info().pickOptions(new NoisePick.Options(maximumFaces)), cancelled)` on a background CPU executor for large surfaces; it is not implicitly scheduled by a context-affine frame method. The query reuses the surface's bounded range index, creates no additional source index/array and performs no native or GL operation.

The [background scalar picker](../renderer/SCALAR-PICKING.md) now supplies that bounded executor through `NoisePickWorker`. `frame.submitPick(worker, x, y, options)` captures this same exact camera/clip/source on the context thread, requires the device's coordinator and independently admits the full CPU source. It performs no synchronous face scan. After successful submission the frame may close without waiting for the query. `info.pickFrame()` and `info.matches(completion)` bind later adoption to image UUID/revision, volume and clip; the host additionally checks its current image/latest pointer revision and invalidates when they change. Only raw borrowed-surface/manual queries require the frame to stay live throughout inspection.

It checks its complete face-count allowance before searching. A too-small allowance yields a LIMIT problem with no partial nearest hit. The hard maximum is 1,048,576 faces. Front-facing bin planes are intersected with the ray's exact near/far interval, then bounded by the other two bin axes. Range slabs conservatively skip missed/behind-nearest groups before original-order face tests. Actual visited-face and tested/culled range counters are returned. The nearest rectangle is selected, then bounded grid-edge lookup recovers its exact original sample. Equal distances compare original sample index then direction; inclusive shared edges select the lower original sample independently of rectangle order. Parallel/back faces are excluded, matching the initial opaque draw policy. Inside-bin back faces are not selectable through culling.

Hits contain detached original volume UUID, sample index, native coordinate/value, direction, display RGBA, distance and local point. They retain no arrays. `GlOrbitCamera.focus(hit)` focuses that point after the host checks the current volume/image adoption receipt. CPU double geometry and GPU float matrix/scale rasterization are not pixel-exact at edges; shared-edge/corner ownership and depth ties require deferred real-driver comparisons. The query does not inspect generated block IDs or infer terrain occupancy.

## Admission and deferred verification

No device/default ceiling is raised. Source payload is the full scalar volume plus nine bytes per unit face or thirteen bytes per greedy rectangle, including prepared RGBA and 24 bytes per 1,024-face range; GPU instances add twelve bytes per emitted rectangle, up to 12 MiB at the existing hard count bound. Preparation additionally admits bounded plane scratch, which is discarded before retained output. A nonempty scene adds one buffer/VAO journal; renderer adds one program/two shaders; each completed image adds its target and frame journals; readback uses its existing two journals/PBO/output. Transferred owners, retired resources and quarantine remain charged. Driver/JVM/native overhead and external borrowed data still need host profiling/admission.

At renderer completion, compare all six face windings/index reconstruction/colors/positions with CPU boundary meshes at fractional/negative grids, different full heights and every scale/anchor limit. Include zero faces, all-selected bins, internal holes, checkerboards, extreme color ranges and camera-inside cases. Verify exact twelve-byte upload totals, minimum budgets, every yield/layout boundary, hostile host state, cancellation/allocation/restoration failures, no partial scene/frame, and retirement/quarantine/source lifetimes.

On genuine Windows GL, compile/link shaders and compare camera/fit/navigation, depth/cull/lighting output, source identity and complete-frame pixels. Exercise rapid source/style/camera/viewport changes, mismatched tokens/devices/contexts, CPU/shared admission failure, transferred old-image survival, independent close, failed replacement, retries and shutdown. Compare noise readback channels/orientation/receipts with original pixels while replacing/closing source wrappers. Verify query plane/bounds/front-face/near/far decisions, empty/budget/cancellation outcomes and source values against independent native samples; quantify float-edge differences before claiming pixel parity. Final editor integration and preview readiness remain pending. No tests ran for this batch.

## Current source and failure recovery

Scalar navigation/clipping now preserves upstream failure diagnostics even if an old scene remains held for cleanup. Redraw, camera fitting and convenience recoloring require the current complete upload. Explicit caller-held source recoloring retains its volume/geometry/admission checks for rapid replacements, and caller-owned frames keep their original pixels and receipts. See [current-source adoption guards](SOURCE-FAILURES.md) for lifecycle and deferred verification. All 129 CPU/OpenGL sources compile; runtime/native/GPU validation remains deferred and unsupported previews stay disabled.

## Clipped display fitting

`fitClipped(width, height)` now uses the current clip and prepared scalar bounds; `fit(width, height)` preserves full-source behavior. Both require the complete adopted current upload. Empty surfaces use their full display-bin extent as the fitting source, then apply the selected scope. Non-intersecting/invalid fits leave the existing view untouched during preflight. See [camera fitting](CAMERA-FIT.md) for conservative bounds, exact anchors and deferred verification.

## Prepared-source recovery

[Explicit upload recovery](SCALAR-RECOVERY.md) now accepts complete exact-volume/style surfaces after a source failure at the same data revision. It preserves camera/viewport/clip, advances the display revision and cancels obsolete CPU adoption before cleanup and new upload admission. `GlNoiseScene.retainInput()` and `GlNoiseFrame.retainSurfaceInput()` produce independently admitted CPU ownership without copying arrays; consuming view recolor/retry overloads hold input through admission or rejection. Runtime/GPU checks remain deferred.
