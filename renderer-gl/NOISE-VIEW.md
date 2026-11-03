# Scalar GPU images, navigation and inspection

Renderer implementation, 2026-11-03. All 116 CPU/OpenGL Java sources compile on Java 25 with LWJGL 3.4.1: 80 CPU and 36 GL. Thirteen native adapters remain syntax-parsed/source-manifest checked only; genuine pinned Windows native compilation is pending. No renderer tests, scalar sampling/query/worker execution, GL context, shader compilation, upload/draw/readback or pixel checks ran. Unsupported previews remain disabled.

This connects the [full-height scalar capture and boundary meshes](../renderer/NOISE-VOLUME.md) to the existing OpenGL device. It renders explicit opaque display bins, independently from Minecraft block geometry, textures, IDs and biomes. It neither evaluates noise nor changes native generation/readiness. The pinned generator's Y-invariant behavior and expression stream's actual 3D evaluation stay in the native producer.

## Compact immutable scene upload

`GlNoiseUpload.begin(device, surface)` borrows a complete immutable `NoiseSurface` and immediately charges its retained sample/mesh payload to a CPU-only device journal. The consuming `RenderInput<NoiseSurface>` overload retains the source through destination admission, requires the same shared coordinator for tracked input, and closes the supplied wrapper on every outcome. Retain a retry owner first if needed.

The admitted instance buffer uses eight bytes per emitted face:

| Word | Encoding |
| --- | --- |
| First unsigned 32-bit word | Original sample index shifted left three bits, plus cardinal direction-enum ordinal 0..5 |
| Second unsigned 32-bit word | Explicit display RGBA8 in `RRGGBBAA` bit order |

The source sample cap is 16,777,216, so the packed sample/direction word fits without truncation. No second full CPU vertex/color/index array is allocated. Existing staging writes whole eight-byte instances, at most 64 KiB per transfer action, in native byte order. The existing per-step byte/action/work ceilings and cooperative elapsed target apply. Storage bytes are reported separately. Color-ramp evaluation and driver calls cannot provide a hard elapsed-time guarantee.

The first storage action admits the complete GPU buffer plus one VAO before names/storage. The instance attribute is an integer pair with divisor one. Layout setup follows complete data transfer. Only full face coverage and successful host-binding restoration allow READY/take. Zero-face surfaces need neither buffer nor VAO and still produce a successful scene. Cancellation or failure retires only upload-owned work under existing fence/quarantine accounting.

`GlNoiseScene` is an independently retainable scene owner. Its journal keeps the exact CPU surface and full scalar volume charged until actual retirement. A last-owner close retires its resources; another retained owner stays live. Pending GPU commands, dependencies and failed cleanup preserve existing device charges. Every operation uses the original thread, GLFW context and LWJGL capabilities.

## Drawing and coordinate bounds

`GlNoiseRenderer.create(device)` owns one GLSL 330 program. For each face instance, the vertex shader reconstructs its original X/Z/Y index, expands the matching outward-wound four-corner bin using six triangle vertices, and applies the original configured horizontal scale. The whole nonempty surface submits one instanced triangle draw. Empty output clears a complete background image without a draw. Statistics count submitted faces/triangles, not surviving pixels.

Opaque depth-tested faces use back-face culling and fixed simple directional lighting with an ambient floor. Display colors do not claim native biome tint, texture or lightmap parity. Interior faces have already been removed by the CPU display mesh. There is no transparency, interpolated isosurface, volume blending, Minecraft material atlas, extra whole-scene sort or clipping-cap geometry. Camera frustum clipping occurs on the GPU; this single-object path introduces no additional CPU section culling.

The grid origin/minimum Y is an exact integer camera anchor. Integer subtraction precedes float conversion through the existing camera-offset contract. Upload rejects nonfinite/subnormal float scale and horizontal relative spans above 2^20. Drawing additionally rejects spacing smaller than four float ULPs at its conservative camera-relative magnitude, preventing bins collapsing into one coordinate. Y remains one block per sample. The CPU source/height/scale is not changed to satisfy these bounds. Initial camera fitting also rejects scenes beyond existing far/relative-camera limits.

Camera matrix/anchor/spacing validation and host-state capture/normalization precede target invalidation. Drawing uses the existing full state guard, including optional host clip-control normalization and active transform-feedback preflight. A validation failure preserves an existing complete target. A failure after target work starts leaves it incomplete. No successful frame/statistics escapes failed drawing/restoration.

## Completed frames and readback

`GlNoiseFrame.render(renderer, revision, scene, camera, width, height, obsolete)` borrows inputs and owns a fresh private target plus an independently retained scene. Target/frame journals depend on the exact scene and image allocation. Only complete drawing/restoration and the final obsolete gate publish the frame. Failure retires newly owned resources and does not alter an old caller-transferred frame.

The detached `Info` records image UUID, display revision, exact volume UUID, original native resource context/grid, inclusion/color style, camera, dimensions and submission counts. It contains no volume/mesh arrays or GL owner. `info.ray(x,y)` uses the original viewport, near/far planes and camera. A top-left image widget must flip the borrowed target texture's V coordinates. Retain an independent frame owner before sharing image lifetime; close owners on the original context.

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
| `redraw()` | Retry/redraw the existing complete GPU scene without native sampling or mesh upload |
| `advance(budget, dataRevision)` | Adopt matching CPU completion, advance at most one bounded upload step, then draw a requested complete image |
| `take(dataRevision, viewRevision)` | Transfer the exact current completed image once |

The host can configure a camera before data arrives, or wait for WAITING_VIEW after upload and call `fit`. Orbit/pan/dolly use the existing immutable `GlOrbitCamera` and feed a new camera to `view`. Threshold/color changes prepare another display mesh from retained immutable volume input; this does not rerun a native sampler. CPU admission rejection preserves previous tokens/work. Source failures remain visible through subsequent camera/resize changes until resubmission. Display failures with a complete scene can be retried by camera change or `redraw`.

CPU completion admission remains live through device-journal admission before its input closes. This conservatively charges overlapping source/destination accounts. Data/view revisions are separate, and an accepted later request cannot revoke caller-transferred frames. Repeated `advance` on an unchanged delivered view performs no redundant upload/draw. Whole-frame drawing is synchronous, as in world/map rendering. Shutdown cancels CPU work nonblocking, retires view-owned uploads/scene/images with retryable cleanup, and leaves independent frame owners live.

## Bounded original-value inspection

Capture `frame.surface()` on the owning context and retain the frame throughout inspection. Run `NoisePick.query(surface, frame.info().ray(x,y), maximumFaces, cancelled)` on a background CPU executor for large surfaces; it is not implicitly scheduled by a context-affine frame method. The query creates no source index/array and performs no native or GL operation.

It checks its complete face-count allowance before searching. A too-small allowance yields a LIMIT problem with no partial nearest hit. The hard maximum is 1,048,576 faces. Front-facing bin planes are intersected with the ray's exact near/far interval, then bounded by the other two bin axes. The nearest face is selected; equal distances retain original deterministic face order. Parallel/back faces are excluded, matching the initial opaque draw policy. Inside-bin back faces are not selectable through culling.

Hits contain detached original volume UUID, sample index, native coordinate/value, direction, display RGBA, distance and local point. They retain no arrays. `GlOrbitCamera.focus(hit)` focuses that point after the host checks the current volume/image adoption receipt. CPU double geometry and GPU float matrix/scale rasterization are not pixel-exact at edges; shared-edge/corner ownership and depth ties require deferred real-driver comparisons. The query does not inspect generated block IDs or infer terrain occupancy.

## Admission and deferred verification

No device/default ceiling is raised. Source payload is full scalar volume plus five CPU bytes per face; GPU instances add eight bytes per face, up to 8 MiB at the existing hard face bound. A nonempty scene adds one buffer/VAO journal; renderer adds one program/two shaders; each completed image adds its target and frame journals; readback uses its existing two journals/PBO/output. Transferred owners, retired resources and quarantine remain charged. Driver/JVM/native overhead and external borrowed data still need host profiling/admission.

At renderer completion, compare all six face windings/index reconstruction/colors/positions with CPU boundary meshes at fractional/negative grids, different full heights and every scale/anchor limit. Include zero faces, all-selected bins, internal holes, checkerboards, extreme color ranges and camera-inside cases. Verify exact eight-byte upload totals, minimum budgets, every yield/layout boundary, hostile host state, cancellation/allocation/restoration failures, no partial scene/frame, and retirement/quarantine/source lifetimes.

On genuine Windows GL, compile/link shaders and compare camera/fit/navigation, depth/cull/lighting output, source identity and complete-frame pixels. Exercise rapid source/style/camera/viewport changes, mismatched tokens/devices/contexts, CPU/shared admission failure, transferred old-image survival, independent close, failed replacement, retries and shutdown. Compare noise readback channels/orientation/receipts with original pixels while replacing/closing source wrappers. Verify query plane/bounds/front-face/near/far decisions, empty/budget/cancellation outcomes and source values against independent native samples; quantify float-edge differences before claiming pixel parity. Final editor integration and preview readiness remain pending. No tests ran for this batch.
