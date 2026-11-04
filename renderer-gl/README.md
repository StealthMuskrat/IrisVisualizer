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

Every allocation already has a journal entry before GL objects are created. Fence/cleanup failure therefore cannot lose its ownership record. An allocation without a usable retirement fence remains quarantined and charged. `device.close()` explicitly finishes GPU work, deletes children before dependencies, releases CPU owners and frees native staging. This final teardown may block and must happen while the original context is current. Context/driver/process-wide failure recovery is not guaranteed; failed deletion does not falsely release a budget charge.

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

Counts include buffers, textures, VAOs, FBOs, renderbuffers, programs and shaders. At most one fence belongs to each allocation journal. Texture bytes use RGBA8; framebuffer bytes reserve four color plus four depth bytes per pixel, conservatively rounding depth24. GPU geometry uses 36 bytes per vertex (168 bytes per quad including indices), or 48 bytes with supplied RGB lighting (216 bytes per quad). This adds 16 or 64 bytes per face to the unchanged CPU format; marker instances add 12 bytes per marker; shared cube arrays add 224 bytes. Each live/retired scene conservatively charges its entire CPU scene primitive payload, even when another allocation shares some inputs.

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
