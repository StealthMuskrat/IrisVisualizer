# Offscreen host-state isolation

Renderer implementation, 2026-11-03. All 105 dependency-free CPU/OpenGL Java sources compile with Java 25 and LWJGL 3.4.1. This batch has not created a GL context, compiled shaders, uploaded/read pixels or executed tests. Genuine Windows driver/native validation remains deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Separate draw, transfer and readback guards

Uploads previously used the same broad state guard as drawing. Every GPU upload step therefore queried bounded viewport/scissor/depth arrays and raster flags, and restored the host program even though transfer work never changed those states. The new transfer guard captures only the bindings/layout it can change. This removes those queries and state writes from scene/map/atlas upload yields and world/map renderer creation; no elapsed-time or driver-performance improvement has been measured.

| Operation | Guard | State handled |
| --- | --- | --- |
| Scene, map and animated-atlas uploads; renderer creation | `GlTransferState` | Active texture, unit-zero 2D texture/sampler, VAO, array buffer, unpack PBO, complete unpack group, draw/read FBO and renderbuffer |
| World/map draws; offscreen target creation/clear | `GlState` plus its transfer guard | Transfer bindings plus program, bounded viewport/scissor/depth-range arrays, buffer-zero blend/color-mask state, depth/cull/winding/polygon/line state, clip distances and supported optional state |
| Completed-image GPU/CPU readback | `GlPackState` | Read FBO/read-buffer selection, pack PBO and complete pack group |

The transfer guard does not bind a program or modify raster/depth/blend state. Renderer creation compiles/links only its newly owned program objects and does not select a current program. Readback remains independent of drawing conventions. New texture/VAO/FBO configuration edits renderer-owned objects only; caller object contents and attachments are not rewritten.

Both pixel-store groups now use `GlPixelStore`: alignment, row length, skipped rows/pixels, image height, skipped images, byte swapping and least-significant-bit order. Tight transfer state uses alignment one and zero for the other parameters, then restores every captured value. Current RGBA8 2D transfers do not depend on several of those parameters, but the complete groups have one consistent capture/restore contract. Pack and unpack PBO bindings are separate from those parameters.

## Fixed drawing convention with optional capabilities

The CPU camera matrix, conservative frustum planes, map canvas and top-left readback expect the backend's existing lower-left OpenGL viewport transform and negative-one-to-one clip-depth volume. A host using upper-left or zero-to-one clip control could previously change clipping and orientation without changing the renderer's camera receipt. Drawing now temporarily selects the expected convention and restores the exact original pair.

| State | Availability | Offscreen drawing | Restoration |
| --- | --- | --- | --- |
| Clip origin/depth mode | GL 4.5 or `ARB_clip_control` | Lower-left, negative-one-to-one | Exact captured origin/depth, core or ARB entry point |
| Depth-bounds test | `EXT_depth_bounds_test` | Disabled | Captured enable; bounds values never changed |
| Fixed-index primitive restart | GL 4.3 or `ARB_ES3_compatibility` | Disabled | Captured enable; restart values never changed |
| Sample shading | GL 4.0 or `ARB_sample_shading` | Disabled | Captured enable; minimum fraction never changed |
| Sample coverage, sample mask and alpha-to-one | Core 3.3 | Disabled | Captured enables; coverage/mask values never changed |
| Polygon smoothing and polygon offset modes | Core 3.3 | Disabled | Captured enables; offset values never changed |

Existing depth/stencil, culling, sRGB, rasterizer discard, dither, multisampling, alpha-to-coverage, logic-op, programmable primitive restart, depth clamp, line smoothing, clip-distance, scissor and blend normalization remains. Viewport arrays remain bounded to 32, as do clip distances. The minimum context is still OpenGL 3.3 core; absent optional capabilities are neither queried nor called. No new framebuffer format, shader feature, native generation behavior or material policy is introduced.

## Preflight, completion and failure

On GL 4.0 or `ARB_transform_feedback2`, full draw-state capture checks active/paused transform feedback. Active unpaused capture rejects with `GlFailure.Code.STATE` before any drawing-state normalization or target invalidation. The renderer does not end, pause or change the host capture. A paused capture is left paused and its original program is restored. Transfer/readback guards perform no program selection, so they do not introduce the earlier unnecessary `UseProgram` restoration during an unrelated active capture.

The host must call drawing outside unpaused transform feedback on every supported context. A minimum 3.3 context without the query capability cannot provide this preflight; the host must enforce that boundary. Likewise, the host must end conditional-render scopes before backend draws and avoid enclosing backend work in host queries whose measured results must exclude it. These command scopes are not reversible state snapshots. Extension-specific raster behavior beyond the explicitly supported table also remains a host integration responsibility.

World/map target invalidation now follows successful capture and normalization. Incoming GL errors, unsupported snapshot bounds, detected active capture and normalization errors therefore preserve an existing complete target and its pixels. Once target work starts, a draw or restoration failure still leaves it incomplete. A fresh target failed during creation is retired. Only complete rendering and successful restoration publish a frame/statistics result; completed caller-owned images remain independent.

Drawing-state restoration uses a nested resource scope so transfer bindings are still restored if its GL check fails. If both restoration phases fail, the drawing failure is primary and the transfer failure is suppressed. Existing upload cancellation, retirement fences, dependency journals, quarantine and retained memory accounting are unchanged. GL calls/cleanup remain synchronous and have no hard elapsed-time guarantee.

## Deferred Windows verification

At renderer completion, compare world/map pixels against a clean-state baseline after each hostile state combination: both clip origins/depth modes, enabled depth bounds excluding the cleared depth, zero sample masks/coverage, alpha-to-one, smoothing/offset modes, both restart enables, sample shading, viewport/scissor/depth arrays, all clip distances and indexed blend/color masks. Cover core entry points, advertised ARB/EXT paths and a minimum 3.3 context without optional capabilities. Confirm every captured state is restored after success and injected draw/restore failures.

Pause scene/map/atlas uploads after every GPU phase while alternating host programs, viewports and raster states. Verify those untouched groups remain unchanged, all eight unpack values and transfer bindings restore, host VAO element state is intact, and copied/updated atlas pixels still match immutable CPU rasters. Exercise readback with all eight pack parameters and nonzero PBO/read-FBO bindings. Include cancellation, setup failure, transfer-only creation under active feedback where legal, unpaused draw rejection, paused draw/program restoration, previous-target preservation and current-context mismatch.

Profile actual query/setup/restore costs on Windows drivers after functional checks. Compilation alone establishes neither runtime capability dispatch, state restoration nor native/GPU pixel parity.

Primary references: [Khronos clip control](https://github.com/KhronosGroup/OpenGL-Registry/blob/main/extensions/ARB/ARB_clip_control.txt), [depth-bounds test](https://github.com/KhronosGroup/OpenGL-Registry/blob/main/extensions/EXT/EXT_depth_bounds_test.txt), [transform feedback 2](https://github.com/KhronosGroup/OpenGL-Registry/blob/main/extensions/ARB/ARB_transform_feedback2.txt), [core enable states](https://github.com/KhronosGroup/OpenGL-Refpages/blob/main/gl4/glEnable.xml). The pinned LWJGL 3.4.1 method signatures were inspected locally.

## Transfer failures remain primary

Renderer implementation, 2026-11-05. All six incremental paths (`GlUpload`, `GlMapUpload`, `GlImageUpload`, `GlNoiseUpload`, `GlAtlasUpload` and `GlReadback`) now preserve a transfer/validation/cancellation RuntimeException or Error if host-state restoration also fails. Each path remembers the original failure, still attempts its captured transfer/pack guard close, suppresses a distinct restoration error onto the original and rethrows the original object. If transfer work succeeds and restoration alone fails, that restoration error remains the primary failure. No completed frame, source or pixel packet is published before successful restoration.

The outer step failure handler therefore classifies an original CancellationException as CANCELLED even when restoration fails; an ordinary transfer error remains FAILED. Disposal is still attempted after restoration, and later distinct cleanup errors are suppressed onto that same original failure. World upload now includes Error as well as RuntimeException in setup/disposal aggregation, including fresh source-lease close when no journal was admitted. Already-device-owned scene wrappers remain protected.

Atlas copy rows use the same rule for source-framebuffer detachment. The source attachment is still detached in finally; copy/completeness failure remains primary if detachment also fails. Host transfer-state restoration then runs independently. If copy succeeds but detachment fails, detachment becomes the primary failure. In a combined failure the order is copy error, suppressed detachment error, suppressed host-restoration error, then suppressed disposal error. Reusing the same exception object does not attempt self-suppression.

| Work/restoration outcome | Step behavior | Publication |
| --- | --- | --- |
| Work succeeds; restoration succeeds | Continue/yield or complete under existing budgets | Existing complete-only rules |
| Work succeeds; restoration fails | FAILED; rethrow restoration error; attempt disposal | No new output |
| Transfer work fails; restoration also fails | FAILED; rethrow original with restoration suppressed; attempt disposal | No new output |
| Cancellation; restoration also fails | CANCELLED; rethrow original cancellation with restoration suppressed; attempt disposal | No new output |

This changes error identity/classification and diagnostic preservation, not the GL calls used to capture/restore host state, transfer/action/work ceilings, cooperative time targets, fence polling, memory budgets, pixel data or native identities. A restoration error still means host state may require host recovery. Resource retirement can also fail; unfinished ownership remains reachable through existing upload/readback owners or device journals, with existing charges. Error aggregation does not assert successful restoration or release.

All 129 production and four harness sources compile with Java 25/LWJGL 3.4.1; the dependency-free set also compiles. All 64 prepared groups remain unexecuted. At renderer completion on real Windows GL, inject runtime exceptions and Errors during each of the six transfer paths, captured-state restoration and disposal, individually and together. Verify exact primary/suppressed identity and order, original cancellation classification, restoration-only failure, no complete-output adoption, current-token queue failure and close retry with conservative retained/retired/quarantined charges. For atlas copy, combine copy/completeness, detachment, host restoration and disposal failure; also check successful copy with detachment-only failure. Exercise all budget yields and zero-timeout readback polls under hostile pack/unpack/binding state. These runtime/driver/failure-injection checks remain deferred; source inspection and compilation are the evidence for this batch.

## Capture and factory setup errors

`GlTransferState` now preserves an exception or Error during its temporary unit-zero texture-binding capture if restoring the original active texture unit also fails. It always attempts that restoration; a restore-only failure still rejects capture, and a distinct later restore error is suppressed onto an earlier capture failure. This covers constructor failure before an incremental upload can own a completed state guard. No additional GL query, binding change or successful-path allocation is introduced.

World-renderer and offscreen-target factories also preserve their setup/TWR restoration error when allocation retirement throws Error, matching the existing map/scalar factories. Distinct retirement errors are suppressed; reusing the same exception does not cause self-suppression. [Device/account rollback](DEVICE-TEARDOWN.md#setup-rollback-before-ownership-escapes) preserves the same ordering through native staging and journal insertion.

At renderer completion, inject capture runtime/Error plus active-unit restoration runtime/Error, restoration-only failure and an identical reused exception. Verify original error identity, suppression, active-unit restoration attempts and no published guard/resource. Include factories with state-capture/restoration and retirement failures, original nonzero active units and host-owned textures. Full and dependency-free compilation passed; all 64 prepared groups and real GL/failure-injection validation remain unexecuted.
