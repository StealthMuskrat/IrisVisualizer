# OpenGL backend

Java 25 / LWJGL **3.4.1**, matching the editor's existing dependency version. This separate library adds forty-one Java sources to the 85-source CPU renderer. All 126 sources compile against the pinned LWJGL classes. No OpenGL context, shader compilation, upload, draw, fence, state-restoration or visible Windows test has been executed for this implementation batch. Renderer tests remain deferred until the stage is implemented; unsupported previews remain disabled.

Compile the library with a Java 25 JDK:

```powershell
gradle -p renderer-gl classes
```

The module depends on `../renderer` and pinned LWJGL core/OpenGL/GLFW Java libraries. The context owner supplies Windows x64 natives. It creates no window, downloads no assets/runtime and changes no editor packaging or readiness gate. The editor's current OpenGL requirement is unchanged; this backend separately requires a **GLFW OpenGL 3.3 core context** with LWJGL capabilities already initialized.

## Context and ownership

Construct `GlDevice` on the thread owning the current context. Every live GPU operation checks the original thread, GLFW context handle and LWJGL capabilities object. Close GPU resources before destroying that context. Context loss/replacement and cross-thread disposal do not silently free accounting or submit work to a different context.

`GlRenderer.create(device)` builds two shader programs and shared diagnostic cube buffers. `GlTarget.create(device, width, height)` creates an owned, single-sample RGBA8/depth24 framebuffer, initialized to transparent color and cleared depth. It does not resize or draw into the host window framebuffer. Target dimension/pixel limits are checked against both configured ceilings and hardware texture/renderbuffer limits.

`GlUpload.begin(renderer, cpuResult)` **consumes a fresh `WorldScene.Result` lease on every outcome** after nonnull argument checks. Do not separately close that result after invoking upload. A CPU-only device journal charges the lease during incremental preflight and keeps renderer dependencies pinned. Preflight failure releases that journal immediately; submitted GPU work retains the lease through actual retirement. A duplicate handoff of a lease already owned by the same device is rejected without closing its owner. `cpuScene()` only borrows data while the GPU scene remains live.

Upload preflight validates the complete scene/raster, hardware atlas size, materials, object counts and combined known CPU/GPU/staging budget. It upgrades the journal before creating base-level textures and per-section vertex/index/marker-instance buffers. Immutable CPU buffer views stream through one explicitly owned native staging allocation, in bounded chunks. Texture transfers use whole tightly packed row groups and explicit unpack state; no full-image direct buffer copy or pixel-unpack-buffer allocation is introduced. Cancellation is checked between sections, faces, row groups and chunks.

`GlUpload.step` now yields under explicit transfer/action/work ceilings and a cooperative elapsed target, restoring host state on every return. `GlUploadQueue` rejects obsolete CPU completions, invalidates queue-owned old work on newer revisions and checks the latest token again before GPU ownership transfer. `GlScene.upload` remains a synchronous wrapper over the same state machine. See [UPLOADS.md](UPLOADS.md) for frame scheduling, progress, ownership and deferred verification; individual driver/storage/cleanup calls are not preemptible.

No partial `GlScene` is returned after failure or cancellation. Already submitted allocations remain in the journal for deferred cleanup, including their CPU lease and budget charges. Driver allocation failure can still occur within configured limits; byte ceilings are not available-VRAM guarantees.

## Drawing and visual boundaries

`renderer.render(scene, target, camera)` draws section-relative meshes into the target. `GlCamera` uses an exact integer world anchor and finite relative eye/target coordinates, computes a perspective/look-at matrix, and subtracts section origins as integers before float conversion. Camera/offset precision bounds and degenerate views fail before drawing.

The geometry pass uses nearest/base-level texture sampling, source normals and simple directional lighting. Optional `WorldLighting` captures instead supply final per-vertex RGB multipliers, without applying the fallback directional light again. Exact scene identity, primitive budgets and lease lifetime are enforced through incremental upload. See [LIGHTING-VIEW.md](LIGHTING-VIEW.md) for the sample boundary, GPU layouts and validation limits. Opaque and binary cutout candidates retain their original indexed ranges and face order. The shader now shares [reviewed terrain alpha thresholds](../renderer/MODEL-MATERIALS.md) with displayed-alpha picking: cutout discards below 0.5 and translucent below 0.1. Native filtered/mipmap and executed pixel parity remain unverified. The simple path now consumes declared shade overrides and a display emission floor; supplied final RGB bypasses that path. No sRGB conversion, native AO/lightmap or native mipmap/interpolation parity is claimed.

`WorldTint` now accepts detached native per-vertex RGB results for the exact CPU scene, and the uploader packs them into normalized color attributes. Missing tint inputs still fail before scene GL storage. `WorldDrawOrder` prepares a bounded global back-to-front centroid order on a worker; `GlDrawPlan` charges/pins the adopted order and rejects stale scene/camera poses before target invalidation. Explicit-plan draw overloads add a depth-tested alpha-blended pass after opaque/cutout geometry. See [MATERIALS.md](MATERIALS.md) for inputs, ownership, budgets and limitations. Centroid sorting does not solve intersecting transparent geometry or establish native OIT parity. Native tint factory/output validation, fluids/special render shapes and lighting still require upstream integration. CPU model/state/biome data remains unchanged.

Sections now use actual emitted geometry/marker bounds for conservative frustum rejection before drawing. Exact vertical camera views use a stable alternate up vector. Consecutive sorted transparent source indices can share draws without reordering. `renderFrame` returns counts after successful draw/restoration; existing void overloads share the same path. Culling does not change native data or release scene budgets. See the [view contract](LIGHTING-VIEW.md).

A second shader draws placeholder markers as instanced magenta unit-cell wire boxes with crossed diagonals. Instance order maps to the source marker list; original block IDs, states and asset problems remain available through the CPU scene. Markers use depth testing rather than replacing terrain data with another block type. The shared cube buffers remain dependency-pinned until all referencing scene VAOs are released.

After successful host-state capture and drawing-state normalization, the target becomes incomplete immediately before target binding/clearing. Rejected preflight leaves an existing completed target usable. Only a successful draw **and** host-state restoration marks it complete again. `textureId()` rejects an incomplete, retired or released target. Its returned GL texture name is borrowed: consumers must not retain/use it after a failed update or target retirement. A later UI integration must adopt only successful frames, using separate targets when it needs to retain an old frame atomically.

The backend snapshots/restores the core state it changes: program, framebuffer/read/renderbuffer bindings, VAO/array/unpack buffers, texture/sampler unit zero, pixel unpack alignment/rows/skips, depth/cull/polygon/line state, color mask/blending at draw buffer zero, relevant enable flags, clip distances, and viewport/scissor/depth-range arrays where supported. It edits only its own texture/FBO/VAO objects. Other extension interactions and actual host-state isolation remain runtime validation requirements. Enter with a clean GL error state; errors are reported rather than silently consumed as success.

## Camera navigation and inspection

`GlOrbitCamera` now supplies immutable fit/orbit/pan/dolly/focus/rebase navigation. `GlCamera.ray` shares its view basis and off-axis near/far clipping convention. `GlScene.retainCpuScene` returns an independent CPU lease for the [bounded native picking layer](../renderer/PICKING.md), which runs without GL calls and preserves native state/biome/visual-problem identity. Camera/index/frame revisions still require application checks; CPU index memory and retained external jobs require combined application admission. No preview or inspection widget is enabled.

## 2D map textures

The [map rendering path](../renderer/MAPS.md) adds `GlMapUpload`, `GlMap` and `GlMapQueue` for CPU-colorized native terrain/biome/noise maps. Uploads charge source samples plus image immediately, expand GPU storage only at the first storage step, stream whole rows, restore host state at yields and expose only complete immutable textures. Latest-revision adoption leaves displayed caller-owned maps alone. Texture row zero uses V=0 for a later top-left image widget. The lower-level uploader creates no shader; the connected canvas below adds presentation while native sampling, editor integration and readiness gates remain separate. Source and GPU charges follow the same device retirement/quarantine contract.

## Completed-frame presentation

`GlFrame` renders into a fresh private target, captures exact successful frame metadata and retains independent image/CPU-inspection ownership. `GlFrameQueue` checks latest revisions/obsolescence before transfer; failed draw/resize work never redraws a prior caller-owned image. Source allocations remain dependency-pinned and changed animated CPU pages are charged explicitly. The [frame/replacement contract](FRAMES.md) also describes multi-epoch scene-cache admission and cleanup-phase guards across upload queues. No editor presentation or readiness gate is enabled.

## Retirement and failure cleanup

The [animation frame layer](ANIMATION.md) now supports immutable GPU atlas snapshots: changed pages use fresh textures, unchanged pages are shared, and incremental GPU copies plus patch rows complete before adoption. `GlAtlasQueue` checks scene/frame revisions and the displayed baseline. Rendering an explicit frame reuses scene geometry and markers. The device's bounded dependency graph retains all source/page/framebuffer parents and transfers already-reserved CPU page charges without changing aggregate allowance. [Base-level interpolation](../renderer/INTERPOLATION.md) now runs through CPU plans and this immutable upload path; native driver parity and filtering remain unverified.

Closing a scene, target or renderer marks its allocation retired and inserts a fence after previously submitted work. `device.collect()` polls fences with zero wait time, deletes eligible objects only after completion, then releases their GPU charge and CPU scene lease. Call it periodically even after a failed upload; retirement does not require another successful draw. Shader/cube dependencies prevent parent resources from being deleted while a scene still references them. A parent may require a later collection pass after its children retire.

Every allocation already has a journal entry before GL objects are created. Fence/cleanup failure therefore cannot lose its ownership record. An allocation without a usable retirement fence remains quarantined and charged. [Device shutdown](DEVICE-TEARDOWN.md) closes the work gate, explicitly finishes GPU work, attempts independent dependency branches after cleanup errors and permits close retry on the original context. Failed children keep parents pinned; statistics remain available while closing. Only complete teardown releases final staging/account ownership. This may block; context/driver/process-wide recovery is not guaranteed and failed deletion does not falsely release a budget charge.

Close methods are idempotent after release. Previously returned Java/native identifiers are borrowed and cannot extend a closed object's legal lifetime. Escaping CPU scene references after owner release is also outside the ownership contract.

## Numerical bounds

| Resource | Default and hard ceiling |
| --- | --- |
| Reserved active/retired GPU payload | 512 MiB |
| Known retained CPU + GPU + native staging payload | 1 GiB |
| Native staging | 4 MiB; configurable down to 64 KiB |
| Allocation journals / object names | 64 / 65,536 |
| Offscreen dimension / pixels | 4,096 / 16,777,216 |
| Captured viewport / clip-distance slots | 32 / 32 |
| Relative camera/section component magnitude | 1,048,576 |

Counts include buffers, textures, VAOs, FBOs, renderbuffers, programs and shaders. At most one fence belongs to each allocation journal. Texture bytes use RGBA8; framebuffer bytes reserve four color plus four depth bytes per pixel, conservatively rounding depth24. GPU geometry uses a 32-byte stride plus a separate four-byte material stride (168 bytes per quad including indices), or a 16-byte material stride with supplied RGB lighting (216 bytes per quad). This adds 16 or 64 bytes per face to the unchanged CPU format; marker instances add 12 bytes per marker; shared cube arrays add 224 bytes. Each live/retired scene conservatively charges its entire CPU scene primitive payload, even when another allocation shares some inputs.

Opaque shader/driver allocation overhead, alignment/storage overhead, native/JNI internal scratch, Java objects/collections, captured ZIP bytes, unrelated cache entries, active/pending input bundles, external native generation work and other application/GPU resources remain outside these byte estimates. These proposed backend limits do not confirm total application memory or alter preview defaults. Admission includes all still-charged retired allocations; the backend never shrinks the world or textures to fit.

## Deferred renderer-stage verification

At stage completion, run a real OpenGL 3.3 core context and representative higher-version contexts on Windows. Compile/link both shaders, verify matrix orientation/winding/UVs at distant anchors, compare GPU output with known CPU/native fixtures, and check target initialization/size/depth/alpha, binary cutout, lighting and marker visibility/picking provenance. Verify native tint capture/packing, global translucent order, alpha/depth behavior, plan identity/camera guards and preserved native inputs; see the material verification matrix.

Exercise streaming across every chunk/row boundary, hostile host unpack/PBO/texture/sampler/VAO/FBO state, per-buffer blend/color masks, indexed/fractional viewports/scissors/depth ranges, clip distances and all modified enable states. Verify restoration on success, cancellation, allocation/shader errors and incomplete frames. Run foreign-thread/context/capabilities and use-after-retirement cases without corrupting the host.

Control fence completion to prove retired resources and CPU leases stay charged; test timeout, failed fence, deletion/quarantine, dependency-pinned shared buffers/pages, repeated close, device teardown and partial-upload cleanup. Check every configured/hardware/object/working limit, multiple live scenes/targets, allocation failure and staging reclamation. Verify/profile incremental scene and atlas scheduling under actual driver stalls before application integration. Native material fidelity, interpolation/mipmap behavior, transparent intersection/OIT handling, editor adoption/readiness and visible GPU QA remain unfinished. No renderer tests were executed in this batch.

API references: [LWJGL OpenGL Java bindings](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL33C.html), [Khronos sync wait contract](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glClientWaitSync.xhtml). Pinned method signatures were checked from the local 3.4.1 JARs; Java compilation does not validate GL execution or GLSL programs.

## Connected world view

`GlWorldView` now owns the route from a fresh complete CPU scene lease through bounded upload and asynchronous transparent sorting to a revision-checked completed image. `WorldRenderWorker` provides the matching single CPU visual/visibility/scene path. World and display revisions are separate; camera/viewport changes do not regenerate voxels, and transferred frames remain independent during replacement. See [PIPELINE.md](../renderer/PIPELINE.md) for native material requirements, CPU/GPU handoff, memory scope, failure/retry and deferred validation. This endpoint now supports externally clocked immutable atlas updates through the connected animation path below; it adds no editor integration or readiness change.

## Connected world animation

`GlWorldView.clock` now routes external animation clocks through one bounded `AtlasFrameWorker`, immutable CPU plans and `GlWorldAnimation` to the existing GPU atlas queue. Completed atlas adoption advances the view revision and redraws without regenerating native data or re-sorting unchanged geometry. Same-pixel clocks coalesce; current atlas/images survive replacements, and one advance spends at most one scene-or-atlas upload budget. See [WORLD-ANIMATION.md](WORLD-ANIMATION.md) for input, admission, diagnostic, cleanup and deferred validation contracts. Interpolation guards and preview gates remain unchanged.

## Connected map canvas and inspection

`GlMapView` now connects background colorization, bounded map upload and `GlMapRenderer` offscreen fit/pan/zoom drawing. Completed `GlMapFrame` owners retain independent targets and shared source-map/native data; `MapPick` inspects the original sample through the exact displayed transform, including transparent colors and explicit absent samples. Exact map/view tokens gate adoption, old transferred images survive source/style/viewport replacement, and cleanup follows the device dependency/fence contract. See [MAP-VIEW.md](MAP-VIEW.md) for texture orientation, memory, failure/retry and deferred verification. No native generation or editor preview is enabled.

## Completed-image readback

`GlReadback`/`GlReadbackQueue` now transfer actual stored pixels from retained `GlFrame`/`GlMapFrame` images through a bounded pixel-pack buffer, nonblocking fence polls and incremental CPU row copies. Results preserve original receipts and top-left RGBA; exact revision adoption withholds stale/partial output. A separate CPU journal keeps READY pixels charged until taken/dropped, and transfer fences follow allocation cleanup. See [READBACK.md](READBACK.md). This adds the capture path for later renderer-stage pixel comparisons; no readback, GL context or test has run.

## Shared CPU/device allowance

`GlDevice(limits, memory)` now admits native staging and every live/retired journal against a shared [RenderMemory](../renderer/SHARED-MEMORY.md) coordinator before allocation. A participating `WorldRenderWorker` keeps preparation and pinned cache ownership under the same ceiling. Charge moves preserve totals; failed cleanup remains charged and successful teardown releases the device account. Other workers and external native/output owners still require separate host admission.

## Connected preparation holds

`GlMapView` and `GlWorldView` now pass a participating device coordinator to their map, atlas and camera-order workers. CPU completions keep shared memory holds through transfer; the views scope those owners across upload/draw-plan admission and rejection. The GPU journal is admitted before the CPU hold closes, so overlap is conservative and never creates an uncharged gap. See [WORKER-MEMORY.md](../renderer/WORKER-MEMORY.md).

## Owned native map inputs

`GlMapView.submit(RenderInput<MapRasterWorker.Request>)` now consumes an [admitted detached native input](../renderer/NATIVE-CAPTURE.md), checks the device coordinator and admits CPU colorization before dropping its source hold. Closed/foreign input and shared pressure do not bypass normal revision, upload, image lifetime or preview gates.

## Geometric clipping

[WorldClip](../renderer/CLIPPING.md) now drives conservative section rejection and fragment clipping in mesh and marker shaders. The world view redraws on clip changes while reusing geometry and complete camera ordering. Frame/readback receipts retain clipping for matching CPU picking; old overloads default to no clipping. No cap geometry, native data reduction or preview gate change is introduced. Real Windows shader and boundary-pixel checks remain deferred.

## Host-state isolation

[Host-state guards](HOST-STATE.md) now separate transfer/creation bindings from full draw state. Upload steps no longer query or rewrite program, viewport, raster or blend state. Pack/unpack use complete core pixel-store groups. World/map draws normalize supported clip-control conventions, depth-bounds testing, fixed restart, sample shading and additional core coverage/polygon flags, then restore them. Detectable unpaused transform feedback is rejected before invalidating an existing target. OpenGL 3.3 remains the minimum; optional enums/functions are used only with their advertised core/extension capability. Real Windows restoration/pixel checks remain deferred.

## Connected scalar-volume images

[Scalar GPU presentation](NOISE-VIEW.md) now connects background `NoiseSurfaceWorker` output to twelve-byte instanced-rectangle upload with [greedy scalar merging](../renderer/SCALAR-GREEDY.md) and original-sample picking, opaque offscreen drawing, immutable `GlNoiseFrame` images and original scalar-face inspection. `GlNoiseView` separates source/display revisions and preserves caller-held images through replacement. Readback supports an exact scalar receipt alongside its existing world/map variants. Camera changes redraw without resampling native values. All shaders/driver/pixel/native checks remain deferred, and unsupported previews remain disabled.

## Model shading and streamed final lighting

[Material lighting](MATERIAL-LIGHTING.md) now connects declared shading overrides/emission to simple drawing without increasing vertex strides. Supplied final RGB remains authoritative. Scoped component readers avoid duplicate full input arrays, optional owned capture retains shared scene/sample admission, and `NativeLightingInputs` provides a guarded explicit native producer boundary. `beginLit` / `submitLit` retain input through device admission, while completed world images/readback carry their actual lighting mode/policy. Native/GPU validation remains pending and unsupported previews remain disabled.

## Original image canvas

The [original image view](IMAGE-VIEW.md) now connects `SourceImage` background decoding to full-dimension texture upload, the shared 2D canvas, display navigation, immutable frames, source-pixel inspection and readback. Source resources have their own receipts, independent of native map data. Hardware/admission limits fail without resizing. All 126 CPU/OpenGL main sources compile; 16 native adapters remain syntax-only, with runtime/native/GPU verification deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Background scalar picking

Completed scalar frames now connect to [NoisePickWorker](../renderer/SCALAR-PICKING.md) through a context-thread submission helper. Queries run on one bounded CPU worker with latest-pointer adoption, exact image/camera/clip receipts and independent full-source admission. Closing a submitted frame does not revoke an admitted CPU query or move GL cleanup off its original context. All 127 CPU/OpenGL main sources compile (86 CPU, 41 GL); runtime/worker/native/GPU tests and final editor integration remain deferred. Unsupported previews remain disabled.

## JPEG source images

The existing original-image GPU view now accepts complete CPU [JPEG images](../renderer/JPEG-IMAGES.md) alongside PNG. Source-format receipts follow unchanged upload, pixel inspection, completed frames and readback; JPEG stored dimensions and opaque decoded RGB remain distinct from viewport pixels. All 128 CPU/OpenGL sources compile (87 CPU, 41 GL). No codec/worker/GPU tests ran; stage validation and final editor integration remain pending, with unsupported previews disabled.

## Background world inspection

World frames now connect to [background index construction and exact-image pointer queries](../renderer/INDEX-WORKER.md). Context-side helpers retain CPU leases and capture exact scene/camera/clip/animated-raster identity; CPU workers own their admission independently of GL frame lifetime. Image tokens and aggregate query bounds complement existing latest-revision guards. All 129 CPU/OpenGL sources compile (88 CPU, 41 GL); index/worker/native/GPU execution and final editor integration remain deferred. Unsupported previews stay disabled.

## Terrain relief and color receipts

Native terrain maps now support [optional CPU slope lighting](../renderer/TERRAIN-RELIEF.md) through the existing map worker/view/upload route. Completed map receipts retain exact style/summary metadata through readback, including terrain light settings and processed-stencil counts. Original native height inspection, alpha, frame ownership and GPU layouts remain intact; flat maps remain available. All 129 CPU/OpenGL sources compile (88 CPU, 41 GL). Renderer-stage execution and final editor integration remain deferred, with unsupported previews disabled.

## Scalar recoloring

The scalar view now accepts [geometry-sharing color requests](../renderer/SCALAR-COLORS.md), including explicit retained displayed surfaces through rapid replacements. Native volume/geometry-policy guards reject stale reuse; camera and clip remain intact. Uploads read prepared RGBA directly through the unchanged twelve-byte instance format, with old/new colors included in CPU/shared admission and old frames preserved. All 129 CPU/OpenGL sources compile (88 CPU, 41 GL); renderer-stage runtime/native/GPU checks remain deferred and unsupported previews disabled.


## Prepared translucent centers

World camera sorting now consumes [prepared translucent face indices and centers](../renderer/TRANSLUCENT-CENTERS.md), avoiding repeated opaque-face scans and vertex-buffer reads. CPU source journals include the metadata, while upload storage/transfer totals use only actual GPU geometry/material/lighting, markers and atlas pixels. Stable global ordering and existing vertex layouts remain intact. All 129 CPU/OpenGL sources compile (88 CPU, 41 GL); runtime/native/GPU checks and editor integration remain deferred, with unsupported previews disabled.

## World solid-face ranges

[Prepared world face bounds](WORLD-RANGES.md) now support conservative frustum/clip rejection inside visible opaque/cutout sections. Adjacent visible groups keep one original material draw; frame/readback stats report tested/rejected intersections and skipped faces, with a low-level comparison mode. CPU metadata stays charged to source ownership and never enters GPU geometry storage. All 129 CPU/OpenGL sources compile (88 CPU, 41 GL); runtime/native/GPU checks remain deferred and unsupported previews stay disabled.

## Current-source adoption guards

[Source/display failure handling](SOURCE-FAILURES.md) now distinguishes a completed current upload from an old GPU owner retained for cleanup across world, map, scalar and image views. Navigation preserves upstream diagnostics; redraw/frame transfer and world animation require current source adoption. Cleanup charges and caller-held old frames retain their existing lifetimes. All 129 CPU/OpenGL sources compile (88 CPU, 41 GL); failure injection, runtime/native/GPU checks and editor integration remain deferred, with unsupported previews disabled.

## Material metadata validation

World upload validation now consumes [prepared material counts](../renderer/MATERIAL-COUNTS.md) per section, preserving missing-tint, exact-raster/material identity and aggregate coverage checks before GPU storage. Face progress advances by validated section coverage; actual transfers keep their existing work/byte/action limits and layouts. Camera sorting uses the scene's verified translucent total for bounded scratch admission. All 129 CPU/OpenGL sources compile (88 CPU, 41 GL); runtime/native/GPU validation remains deferred and unsupported previews stay disabled.

## Explicit upload recovery

[Current-revision upload retries](UPLOAD-RETRY.md) now reopen failed world/map/image queues after cleanup, and the world view can accept retained complete CPU data at the same world token. Retry preserves display configuration and requires fresh source adoption. Atlas replacement also retains one previous owner through retirement and status failures. All 129 CPU/OpenGL sources compile (88 CPU, 41 GL); failure injection, runtime/native/GPU validation and editor integration remain deferred, with unsupported previews disabled.

## Owned readback pixels

[CPU readback owners](READBACK-OWNERS.md) now preserve managed output admission after direct or revision-checked queue transfer, without copying pixels or retaining GL sources. Independent inputs can outlive device shutdown and release their shared charge on final close; handoff admission/setup rejection preserves READY output for retry. Raw transfers remain available with external accounting. All 129 CPU/OpenGL sources compile (88 CPU, 41 GL); runtime/native/GPU validation and editor integration remain deferred, with unsupported previews disabled.

## Clipped camera fitting

[World/scalar fit helpers](CAMERA-FIT.md) now support explicit `fitClipped` navigation using current geometric clipping, alongside full-source fitting. Section/surface-bound intersections keep exact anchors, existing camera limits and empty-source fallbacks; non-intersecting scopes fail before view mutation. No native samples, geometry or clip settings are changed. All 129 CPU/OpenGL sources compile (88 CPU, 41 GL); runtime/native/GPU checks and editor integration remain deferred, with unsupported previews disabled.

## Picking preparation admission

Completed-frame background index requests now inherit [prepared picking-size receipts](../renderer/PICKING-SIZES.md), reserving their actual source/index/build primitive demand without synchronous section/face scans during handoff. The same complete CPU source, ray/clip/animated-atlas contracts and independent frame/index ownership remain in place. All 129 CPU/OpenGL sources compile (88 CPU, 41 GL); runtime/native/GPU validation remains deferred and unsupported previews stay disabled.

## Captured map recoloring

`GlMapView` now supports [recoloring exact native map captures](../renderer/MAP-RECOLOR.md), including current-upload and retained-raster/owned-input forms. A capture UUID rejects obsolete sources during rapid replacements; ordinary revision/upload ownership and matching-grid pan/zoom remain in place. CPU work reserves both old/new image payloads and reuses equal value styles. All 129 CPU/OpenGL sources compile; runtime/native/GPU validation remains deferred and unsupported previews stay disabled.

## Scalar upload recovery

[Explicit scalar recovery](SCALAR-RECOVERY.md) now restarts a failed source upload from complete retained CPU data at the same data revision, checking exact volume/style and lowered output limits. Independent scene/frame CPU inputs survive GL closure and feed consuming recolor/retry overloads. Camera, viewport, clipping and old completed frames retain their existing contracts. All 129 CPU/OpenGL sources compile; runtime/native/GPU checks remain deferred and unsupported previews stay disabled.

## Retained raster upload recovery

[Native-map and original-image recovery](RASTER-RECOVERY.md) now retries complete CPU rasters at the current source token, with exact map capture/style or decoded image content receipts. Independently admitted raster inputs survive GL/frame closure and feed consuming retry/recolor paths without copying pixels. Navigation and old completed frames retain their existing ownership. All 129 CPU/OpenGL sources compile; runtime/native/GPU checks remain deferred and unsupported previews stay disabled.

## CPU validation preparation

[Initial dependency-free CPU contracts](../renderer/CPU-VALIDATION.md) now compile alongside the renderer, preparing 19 deterministic groups before stage execution. They cover supplied map/scalar/image data and ownership, including independent exposure/light expectations; no GL context, driver, native parity or retry behavior is certified. All 129 production sources plus the harness compile; runtime tests remain deferred and unsupported previews stay disabled.

## World CPU validation preparation

[Prepared world contracts](../renderer/WORLD-CPU-VALIDATION.md) now supplement the map/scalar/image suite with model-to-atlas geometry, scene/cache ownership, picking and translucent order checks. Both harnesses compile alongside all 129 production sources; the runner remains compile-only by default. No contract, native or GPU execution occurred and unsupported previews stay disabled.

## Prepared context-free display math

[Camera, ray and frustum contracts](MATH-VALIDATION.md) now prepare 13 groups without an OpenGL context or LWJGL dependency. Analytic projection/navigation and direct homogeneous-corner culling expectations complement scalar full/clipped fitting. The compile-only runner includes the bounded helper set; all 129 production sources plus three harnesses compile. No tests or GPU operations ran and unsupported previews stay disabled.

## Readback preflight and recovery

[Readback replacement guards](READBACK-RECOVERY.md) now validate complete targets, exact device and lowered limits before discarding prior work or READY pixels. An explicit current-failure retry reopens the same token only after cleanup, preserving retirement/quarantine ownership. All 129 production sources plus three harnesses compile; runtime/native/GPU checks remain deferred and unsupported previews stay disabled.

## Owned animation plan handoff

[Atlas CPU ownership](../renderer/ANIMATION-OWNERS.md) now connects retained plan inputs to upload/queue admission with matching-coordinator checks, including the world animation controller. Exact raster baseline, old-frame lifetime and page sharing stay intact. Eleven additional animation contract groups are prepared; all 129 production sources plus four harnesses compile. Runtime/native/GPU validation remains deferred and unsupported previews stay disabled.

## Atlas replacement preflight and recovery

[Atlas queue guards](ATLAS-RECOVERY.md) now preserve earlier same-scene work and untaken READY atlases when a replacement displayed frame or plan fails preflight. Exact FAILED scene/frame pairs can reopen only after cleanup; obsolete scenes retain their existing invalidation behavior. Follow-up compilation in the restored workspace covers these changes; runtime/GPU validation remains pending and unsupported previews stay disabled.

## Map and image replacement preflight

[Raster queue guards](RASTER-PREFLIGHT.md) now validate complete sources and hardware/row-staging limits before replacing active or untaken output. Direct upload begin shares the same checks. Restored compilation also covers the preceding atlas preflight/retry fix; all 129 production sources plus four harnesses compile. Runtime/native/GPU validation remains deferred and unsupported previews stay disabled.

## World replacement preflight

[World scene/material guards](WORLD-PREFLIGHT.md) now validate complete source and exact tint/lighting identity before queue/view invalidation. Missing required tint is rejected using prepared counts before journal admission; automatic native tint reuses captured metadata. Fresh-wrapper consumption and already-device-owned protection remain intact. All 129 CPU/OpenGL sources plus four harnesses compile; runtime/native/GPU checks remain deferred and unsupported previews stay disabled.

## Completed-frame replacement preflight

[Frame metadata/target guards](FRAME-PREFLIGHT.md) now preserve earlier untaken world images when source/order/atlas identity, camera pose or target limits reject a replacement. All four direct frame types and connected viewports share target-limit checks before source retention/journal admission or display mutation. Direct readback also validates its borrowed target and budgets before retaining any of the four frame types; accepted drawing/cancellation and admission cleanup retain their existing paths. All 129 CPU/OpenGL sources plus four harnesses compile; runtime/native/GPU checks remain deferred and unsupported previews stay disabled.

## Upload recovery capability guards

[Map/image recovery](RASTER-RECOVERY.md) and [scalar recovery](SCALAR-RECOVERY.md) now validate upload capabilities before recovery token changes, worker invalidation or retained-owner cleanup. Map dimensions and scalar grid precision are also checked before connected background preparation, preserving earlier work/output on unsupported submissions. Direct upload and retry share the guards; all 129 production sources plus four harnesses compile. Runtime/native/GPU checks remain deferred.

## Camera-order reservation visibility

`GlWorldView.orderReservedBytes()` now exposes [sorting-worker lifecycle totals](../renderer/WORKER-MEMORY.md), including cancelled active scratch until unwind. Conservative source-cleanup overlap and caller-owned result charges remain enforced. Prepared sorting and shutdown-recovery groups compile, bringing the total to 64 unexecuted groups; runtime/GPU validation remains deferred.

## Shutdown after CPU worker errors

[View and animation shutdown](SOURCE-FAILURES.md) now attempts remaining GPU cleanup after worker close/invalidation failure and aggregates terminal-status errors without masking the first failure. World shutdown closes both owned workers before GPU owners; standalone animation keeps its borrowed-worker contract. All 129 production sources plus four harnesses compile; the 64 prepared groups and native/GPU failure paths remain unexecuted.

## Retryable queue disposal

[All six GPU queues](SOURCE-FAILURES.md#queue-shutdown-and-terminal-publication) now retain shutdown retry eligibility until cleanup and CLOSED publication both succeed. Cleanup errors remain primary when terminal metadata also fails, and the closed gate blocks further adoption throughout recovery. Original-context validation precedes teardown, atlas CLOSED publication reads no failed-upload progress, and independently transferred outputs retain their ownership. Close queues before their device. Full and dependency-free compilation passed; all 64 prepared groups and GPU/runtime validation remain unexecuted.

## Animation worker admission preflight

[Standalone animation construction](../renderer/ANIMATION-OWNERS.md#worker-validation-before-atlas-capture) now rejects closed/exhausted workers and tracked coordinator mismatches before retaining GPU atlas ownership. Validation preserves pending/READY CPU work and tokens; tracked matching and untracked compatibility remain available. The retained-completion contract now prepares these ownership cases without adding a group. All production/harness sources compile; all 64 groups and GPU/runtime validation remain unexecuted.

## Complete resource-bundle cleanup

[Scene ticket release](../renderer/SCENES.md#complete-cleanup-attempts) and [atlas capture cleanup](ANIMATION.md#atlas-capture-cleanup) now attempt every remaining owned resource after a release error while preserving the first failure. Capture also attempts to close an already-created frame if temporary-owner cleanup prevents its return. Existing cache/device accounting and consumed-owner semantics remain intact. Full and dependency-free compilation passed; the expanded cache-ownership cases remain within 64 prepared, unexecuted groups.

## View setup ownership

[Map/image/world construction](SOURCE-FAILURES.md#view-construction-before-worker-ownership) now completes empty queue setup before creating workers. World setup checks the caller-bounded atlas worker before creating ordering and preserves the original error if rollback close also fails. Borrowed renderer/device ownership, worker defaults and successful behavior remain unchanged. All production/harness sources compile; runtime/GPU failure injection and all 64 prepared groups remain unexecuted.

## Transfer error preservation

[All six incremental transfer paths](HOST-STATE.md#transfer-failures-remain-primary) now preserve original transfer/cancellation errors through host-state restoration and disposal failure. Atlas copy also preserves its original error through source detachment; world-upload setup aggregates source/journal cleanup errors. Restoration-only failure still prevents publication. Full and dependency-free compilation passed; all 64 prepared groups and runtime/GPU validation remain unexecuted.

## Retryable device shutdown

[Device teardown](DEVICE-TEARDOWN.md) now preserves its first error while visiting independent eligible allocations, keeps failed dependencies charged and blocks new GPU work after shutdown starts. Close retry and context-affine statistics remain available; a failed allocation is visited once per attempt. Retirement/collection also quarantine Errors and retirement rejects a zero fence explicitly. Full and dependency-free compilation passed; all 64 prepared groups and Windows GPU/runtime validation remain unexecuted.

## Device and resource setup rollback

[Setup rollback](DEVICE-TEARDOWN.md#setup-rollback-before-ownership-escapes) now catches Errors after device-account creation, frees temporary staging when possible and preserves conservative charges if native release fails. Staging exhaustion keeps its MEMORY code and now retains its original cause. Journal insertion, world-renderer/target retirement and temporary texture-unit capture preserve original setup errors through rollback failures. All production/harness sources and the dependency-free set compile; all 64 prepared groups and runtime/GPU failure-injection checks remain unexecuted.

## Consuming input cleanup

[Owned GPU handoffs](INPUT-HANDOFFS.md) now retain newly created atlas/scalar/lit-world upload jobs until supplied CPU input cleanup succeeds. A cleanup failure attempts to close the unreturned job while preserving the first error. Paired lighting/scene and raw world submission also attempt fresh-owner cleanup without masking the original rejection. Full and dependency-free compilation passed; all 64 prepared groups and runtime/GPU failure injection remain unexecuted.

## Failure diagnostic preservation

[All eleven queue/view/animation publishers](SOURCE-FAILURES.md#best-effort-failure-diagnostics) now preserve the original error if diagnostic message, metadata or status construction also fails. Atlas invalidation likewise preserves cleanup errors through STALE publication failure and keeps its obsolete gate authoritative. Successful status formats, source ownership and memory limits retain their existing contracts. Full and dependency-free compilation passed; all 64 prepared groups and runtime/GPU validation remain unexecuted.

## Precise scalar focus receipts

[Scalar focus](CAMERA-FIT.md#fractional-scalar-focus-at-distant-origins) now reconstructs sample positions from immutable hit-grid metadata before subtracting distant anchors, preserving decimal spacing near world-coordinate limits. Produced hits supply the receipt automatically; legacy hit construction/focus remains available. Expanded navigation and picking contracts compile within 64 prepared, unexecuted groups. Full and dependency-free compilation passed; runtime/native/GPU validation remains deferred.

[Background visual reports](../renderer/REPORT-WORKER.md) now have completed-frame handoff via `submitVisualReport`, shared-coordinator checks before source retention, compact `Info.visuals()` assignment receipts and report-completion matching. Real frames attest their exact assignments; earlier Info constructors retain empty optional metadata. Census/diagnostic work runs on the CPU worker, and detached reports can outlive their source frame/scene. All 136 production/harness sources compile; 68 prepared groups remain unexecuted. Actual GL handoff, native/GPU validation and editor report adoption remain pending.

[Histogram companions](../renderer/HISTOGRAM-HANDOFFS.md) now survive opted-in CPU preparation through connected map/scalar view upload. Current-source histogram retention, inherited recolor settings, matching-companion retry and complete cleanup preserve independent caller ownership. Real scalar map frames include optional exact source identity; map and volume frame receipts match histogram source/range without scanning data. Compilation passed; all 72 prepared groups and actual GL/thread/native/UI adoption remain unexecuted.

[Independent histogram requests](../renderer/HISTOGRAM-WORKER.md) now capture admitted CPU owners from completed map/scalar frames for off-thread current-range inspection or automatic range suggestions. Worker/coordinator preflight precedes source retention. Frame receipts separately attest exact source identity or source plus current display range; detached output outlives its source frame. All 138 production/harness sources compile, and all 72 prepared groups remain unexecuted. Actual GL handoff, thread/native/GPU validation and editor adoption remain pending.

[Prepared Windows GPU validation](GPU-VALIDATION.md) now adds ten explicit real-context groups for shaders, original image/map pixels, navigation, upload/readback recovery, host state, scalar clipping and detached lifetimes. `GlDevice.driver()` exposes bounded immutable context provenance without changing readiness or thread guards. The existing library/native ownership remains intact; the new PowerShell runner compiles by default, while only explicit stage execution loads its isolated Windows native bundles and may publish a successful run report. All 139 production/harness sources compile. The 72 CPU/context-free groups and ten GPU groups remain unexecuted; real native/GPU and final editor validation are still pending.

[Scalar GPU geometry reuse](SCALAR-GPU-RECOLOR.md) now separates eight-byte geometry and four-byte colors while keeping twelve GPU bytes per face. Recolor uploads copy exact retained geometry on the GPU, transfer only colors from the CPU, restore copy bindings and preserve old frames under normal admission/fenced retirement. Connected scalar views retain matching scenes across CPU recolor replacements. The new prepared GPU group covers copy totals, full-upload pixel equivalence, batch offsets, rejection/cancellation/ownership, empty scenes and connected rapid recolor/histogram lifetime. All 139 sources compile, and all 83 groups (72 CPU/context-free, 11 GPU) remain unexecuted.

[World GPU contracts](WORLD-GPU-VALIDATION.md) now extend the explicit Windows harness beyond world shader compilation with five prepared groups for textured neighbor-cull meshes/native identity, cutout and ordered transparency, atlas animation/immutability, tint/final lighting/host state, and empty/diagnostic/recovery paths. A separate test-only detached fixture uses ordinary bounded CPU preparation and retains no production readiness claim. All 140 renderer/test files compile, including 134 production sources, five harnesses and the fixture; all 88 groups (72 CPU/context-free, 16 GPU) remain unexecuted.

[Shared translucent culling and draw scratch](TRANSLUCENT-CULLING.md) now extend prepared world face bounds across opaque/cutout and sorted translucent passes, with one cached conservative decision per group, unchanged surviving order and exact submission diagnostics. Temporary section/index/cache data receives CPU-only device admission before drawing and releases after helper-stack unwind. Rejected admission preserves the prior complete target and host state. New context-free/GPU groups prepare cache/clip/budget behavior and immutable differential pixels. All 141 sources and the 103-file dependency-free set compile; all 90 groups remain unexecuted.

[Scalar draw admission](SCALAR-DRAW-ADMISSION.md) now covers bounded camera/frustum/group data with a CPU-only device journal through helper-stack unwind and host restoration. Upload/view and drawing share the grid-only float precision check. Separate immutable frame modes prepare enabled/disabled culling comparisons while preserving original source/style/clip receipts. New context-free/GPU groups prepare analytic bounds, exact accounting, deficient admission and precision cleanup without target/state changes. All 142 sources and the 104-file dependency-free set compile; all 92 groups remain unexecuted.

[Immutable histogram reuse](../renderer/HISTOGRAM-REUSE.md) now avoids a second full-source histogram pass when source, range and bins match. Requests and connected views retain existing memory admission and fallback to capture for changed metadata. All 142 sources and the 104-file dependency-free set compile; all 93 prepared groups remain unexecuted. Native/GPU validation and final integration remain pending.

[Bounded transparent-face sorting](../renderer/BOUNDED-FACE-SORT.md) now halves temporary ordering payload to 16 bytes per face using two arrays, with a zero-move fast path for already ordered inputs and unchanged deterministic depth/source-key ties. Admission, cancellation and immutable output remain bounded. All 143 renderer/test sources and the 105-file dependency-free set compile; all 94 groups remain unexecuted. Renderer/native/GPU validation and final integration remain pending.

[GPU transfer replacement admission](REPLACEMENT-ADMISSION.md) now preserves accepted uploads, readback/atlas output and connected world frames when a fresh transfer cannot obtain its initial memory journal. All five transfer queues admit before retiring old work or publishing a replacement token, with conservative overlap and fresh-owner cleanup. Full compilation covers 143 files; all 96 prepared groups (76 CPU/context-free, 20 GPU) remain unexecuted. Native/GPU validation and final integration remain pending.

[World material refresh](WORLD-MATERIAL-REFRESH.md) now separates geometry and material storage, copies unchanged geometry/indices/markers/base atlas on the GPU and uploads replacement tint/final RGB only. Queue and connected-view entry points preserve exact CPU provenance, bounded overlap and immutable output, without retaining GPU ancestry chains. All 143 sources compile; all 98 prepared groups (76 CPU/context-free, 22 GPU) remain unexecuted. Native/GPU validation and final editor integration remain pending.

[Owned lighting and animation recovery](WORLD-CONTROL-RECOVERY.md) now keep supplied final-lighting admission live through queue/view material replacement and re-arm failed animation using the complete current scene. Initial-atlas compilation now passes alongside all 143 renderer/validation sources; all 100 groups remain unexecuted. Native/GPU/runtime validation and final editor integration remain pending.

[Displayed atlas material refresh](DISPLAYED-ATLAS-REFRESH.md) now carries complete animated pixels/clock into independent replacement storage and preserves exact GPU-scene raster receipts. Raw/owned queue/view handoffs retain changed-page CPU payload under the existing ceilings. All 143 sources compile; all 102 groups remain unexecuted. Native/GPU/runtime validation and final integration remain pending.

[Worker abandonment recovery](../renderer/WORKER-ABANDONMENT.md) now clears pending/READY ownership before fallible release across atlas, client-asset, image, visual and pointer workers, preserving primary errors and uninstalled-hold cleanup. Full/default source sets compile (143/105 files); all 103 prepared groups remain unexecuted. Native/GPU/runtime validation and final integration remain pending.

[Bounded live playback](LIVE-PLAYBACK.md) now coalesces continuously advancing clocks behind one completing atlas update, while explicit seeks retain immediate preemption. Initial live presentation waits for a completed sample; pause, navigation, material/world replacement and retry preserve actual displayed receipts. Full and dependency-free compilation pass (143/105 files); all 105 prepared groups (77 CPU/context-free, 28 GPU) remain unexecuted. Native/GPU validation and final integration remain pending.

[Transparent material-order reuse](MATERIAL-ORDER-REUSE.md) now retains a complete exact-scene face order across raw/owned material refresh and admits a new destination-only journal without sorting or copying its immutable keys. Pose/world invalidation, conservative overlap, redraw recovery and complete owner cleanup remain enforced. Full/default compilation covers 143/105 files; all 108 prepared groups remain unexecuted. Native/GPU validation and final integration remain pending.

[Sparse atlas clocks](../renderer/SPARSE-ATLAS-CLOCKS.md) now index time-varying sprites and reuse conservative canonical sampling intervals, avoiding full-palette preflight for unchanged clocks. Static/repeated-tile metadata, complete raster receipts, exact patch/page admission and source lifetimes remain preserved. Both compilation sets pass (143/105 files); all 112 prepared groups (80 CPU/context-free, 32 GPU) remain unexecuted. Native/GPU validation and final integration remain pending.

[Hierarchical original-range picking](../renderer/PICKING-RANGE-HIERARCHY.md) now skips complete face/marker group subtrees inside reached sections while preserving original indices, clipping, alpha and deterministic nearest-hit ties. Verified section-family counters keep worker admission exact, with explicit linear/exhaustive modes and bounded node work. Both compilation sets pass (143/105 files); all 115 prepared groups (83 CPU/context-free, 32 GPU) remain unexecuted. Native/GPU validation and final integration remain pending.
