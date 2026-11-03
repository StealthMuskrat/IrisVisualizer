# Native decisions to complete world images

The renderer now has two connected endpoints: `WorldRenderWorker` prepares a complete leased CPU scene from a frozen visual request, and `GlWorldView` uploads that scene, sorts its transparent faces asynchronously and produces independently owned completed images. This is renderer implementation, with no editor hookup, native producer selection or readiness change.

## Native input and one CPU task

Capture a `WorldVisuals.Request` using the exact native snapshot, selected client assets, explicit choice palette and per-cell native choices. `WorldVisibility.Input` captures native directional masks before an atlas exists, tied to the exact snapshot. It rejects missing cells, undefined bits and exceeded limits. `Input.keepAll` is an explicit alternative that performs no opacity inference. Once visual preparation creates an atlas, `bind` shares the privately captured immutable mask with that exact layout. Native mask producers remain outside the renderer.

`WorldRenderWorker.Request` requires both inputs to refer to the same exact snapshot object. It also accepts lower scene/section limits and a combined job working ceiling. The worker runs visual resolution, texture/atlas preparation, visibility binding, complete-volume scene planning and section assembly sequentially on one daemon CPU thread. It does not chain independently overlapping visual/scene workers or retain their intermediate completions in an extra queue.

One active request and one replaceable pending request are retained. Accepted submission cancels obsolete active work, replaces pending work and drops any untaken completed scene lease. Rejected admission leaves the current revision, status and completion intact. `invalidate()` advances the revision immediately without requiring replacement input. `take(expectedRevision)` transfers one complete scene owner exactly once for the current token; stale tokens cannot obtain it. Status contains detached generation/snapshot receipts and bounded diagnostics only.

`Completion.owner()` is a `WorldScene.Result` lease. Close it if it will not be uploaded, or transfer it to `GlWorldView.submit`/`GlUploadQueue.submit`, which consume fresh nonnull owners on every outcome. Do not close the same wrapper after transfer. `retain()` gives another independent owner if native material preparation, inspection or another consumer must continue separately. Cancellation, failed assembly and stale completion close all worker-owned scene tickets. Transferred owners remain caller-owned, including during worker shutdown.

`WorldRenderWorker.ModelRequest` also accepts frozen native state decisions from `NativeModelInputs.capture`. Model inspection enters a separate `INSPECTING` status on this same CPU thread, before visual preparation. Pending requests retain no native context or choice-index arrays; assignment/copy payloads are admitted before allocation. See [MODEL-INPUTS.md](MODEL-INPUTS.md) for deterministic ticket, native shape, placeholder and budget boundaries.

The [native visibility bridge](NATIVE-VISIBILITY.md) now supplies masks from the actual vanilla block-face predicate. `NativeSceneInputs.capture` can capture native offsets, model decisions and visibility in one borrowed session, then hand off a complete detached `ModelRequest`. Streaming mask capture avoids an extra full-volume source array; native validation and final application admission remain pending.

The [weighted native input adapter](WEIGHTED-CHOICES.md) can now return the existing ready `WorldRenderWorker.Request` after inspecting bounded selection descriptors and capturing actual per-cell native choices, offsets and visibility under one borrowed session. Its dedicated random source must match the pinned renderer. The queue retains no native random/list/session object; JSON geometry/textures/scenes still prepare on the same CPU worker. Genuine native/factory/pixel validation remains pending.

## CPU admission across phases

| Resource | Default / hard ceiling |
| --- | --- |
| Frozen directional masks | One byte per cell, at most 16 MiB |
| One complete render job, including resident cache payload | 512 MiB |
| Shared section mesh arrays | 256 MiB |
| Active job/cache plus one frozen pending input | 1 GiB |
| Sort scratch and output arrays per camera job | 32 MiB |
| Sort source plus scratch/output per camera job | 512 MiB |
| Active plus pending camera sort reservations | 1 GiB |

Visibility input capture copies caller masks; later binding does not create a second full-volume copy. `WorldVisuals.Request.withWorkingBytes` lowers the visual working allowance while sharing its already frozen input. Its allowance cannot grow or fall below retained input bytes.

Before visual preparation, prior cache admissions and unleased entries are discarded. Outstanding displayed, GPU or inspection leases keep their sections and source epochs pinned. The worker deducts retained cache bytes and frozen visibility bytes from the visual working allowance, so old scenes, selected assets, original native indices, immutable offset/tint palette/indices, choices and prospective geometry/texture/atlas arrays are counted together during this phase. A pinned old scene can explicitly block the next request instead of forcing truncation or replacing native data.

During scene construction, the original request still retains its catalog and supplied native packet arrays. The worker subtracts those bytes from the scene/cache working ceiling; only the native snapshot indices, immutable offsets/tints and privately shared visual-choice indices are deducted as known shared source data. Raw native packets that also appear in the new scene may therefore be conservatively charged twice. Existing complete cache admission checks include old pinned epochs alongside all new scene sections before output allocation. Intermediate visual preparation graphs unwind before scene-building admission; all per-job temporary graphs unwind before the active reservation is removed.

The worker reserves the active job's full working ceiling plus the pending request's frozen input bytes. When idle, it counts resident cache payload plus the next frozen input. Each job's declared ceiling must also fit the worker ceiling. A pending request cannot quietly exceed its reservation while waiting; it starts its own phase admission when active. Lower worker/job limits are supported, with explicit failures when old pinned scenes cannot coexist with the request.

These are known primitive payload/count bounds. Model/object metadata, parser/decoder scratch, generation/native memory and independently retained external references are excluded. CPU scene caches, sorting workers and `GlDevice` each enforce their own limits; a final application must additionally admit their combined concurrent payload, caller-held frames, native jobs, maps and inspection data. These endpoints do not establish the complete application memory budget or enable previews.

## GPU view endpoint

Create `GlWorldView(renderer)` on the renderer's current owning context. It borrows the renderer/device and owns an upload queue, frame queue, sorting worker, current GPU scene and camera-bound draw plan. It creates no window or GL context.

| Operation | Effect |
| --- | --- |
| `invalidate(worldRevision)` | Immediately rejects older CPU/GPU/frame work and retires view-owned prior work |
| `submit(worldRevision, owner, tint, lighting)` | Consumes a complete fresh CPU scene lease and starts bounded GPU upload |
| `view(camera, width, height)` | Validates camera/target bounds and advances the display revision when the view changes |
| `advance(uploadBudget, latestWorldRevision)` | Advances current uploads, polls current sorting and renders a complete requested image when ready |
| `take(worldRevision, viewRevision)` | Transfers the matching completed image exactly once |
| `clock(tick, partialTick)` | Requests externally clocked atlas animation without regeneration or re-sorting geometry |
| `redraw()` | Explicitly retries/redraws the current view without regenerating native output |
| `close()` | Cancels CPU sorting and retires view-owned resources; leaves the renderer/device and transferred frames caller-owned |

The host should invalidate both CPU and GPU endpoints immediately when native input changes, before generation/visual preparation completes. Poll the CPU worker with the matching current token and submit its fresh completed owner to the view. Configure a camera/viewport, call `advance` from the context thread, then adopt only a `take` matching both current world and view revisions. Repeated `advance` calls for an unchanged completed view perform no redundant upload, sort or draw.

Native tint/lighting, when supplied, must belong to the exact transferred CPU scene. The [captured tint palette](TINT-INPUTS.md) travels through the scene source and yields a zero-copy `Completion.tint()` view; `GlWorldView.submit(completion)` transfers it with the scene owner. Upload automatically binds captured colors when no explicit tint is supplied. Tint-required faces without complete native tint inputs still fail during existing upload validation. Simple lighting remains supported when native lighting is absent; no biome colors or native light policy are invented. Native material production must occur outside the render-thread pump using the existing capture contracts and separate ownership/admission.

World and camera revisions are independent. Orbit, pan, dolly and resize never rebake geometry or regenerate the native volume. Equal camera/size input is a no-op. A viewport/FOV change with the same exact sort pose can reuse an existing or in-flight face order; pose changes cancel the old sort. Untinted opaque/cutout-only scenes need no sort job. Transparent scenes retain a CPU owner while `WorldOrderWorker` performs the existing bounded stable global centroid sort. Its completion must match the exact current scene and camera pose before `GlDrawPlan` admission. Failed/stale sorts do not permit an incomplete transparency pass. Centroid sorting still does not solve intersecting surfaces or establish native OIT fidelity.

Only a complete GPU upload becomes the view's scene. Only a successful draw and host-state restoration publish a new `GlFrame`. The returned image is independent of the view's next request; resizing, navigating, invalidating, closing or failing a replacement does not redraw or close caller-held images. Keep each image owner live through its last submitted use and release it when replaced. Its retained scene/raster and camera receipt remain available for native picking.

Upload steps obey the existing byte/action/work limits and cooperative elapsed target. Drawing a whole admitted scene remains synchronous; individual driver calls and frame rendering have no hard elapsed guarantee. Failed cleanup leaves resources charged under existing retirement/quarantine rules. `close()` retries unfinished retirement rather than treating a CLOSED queue status as proof of release. Close the endpoint and its transferred image owners before destroying the original context/device.

This endpoint now connects external `clock` inputs through one CPU atlas worker and immutable GPU frame adoption. Animation adoption advances the view revision and renders with the exact updated raster; same-pixel clocks coalesce and camera changes can keep drawing the current atlas while an update is prepared. One advance steps either scene upload or atlas upload under the caller's budget. Caller-held images preserve their original pixels, atlas and clock receipt. See [WORLD-ANIMATION.md](../renderer-gl/WORLD-ANIMATION.md) for worker admission, cancellation, diagnostics and retry contracts. The separate [map-view endpoint](../renderer-gl/MAP-VIEW.md) now connects native map colorization/upload to immutable pan/zoom canvases and exact original-sample inspection. Native interpolation and final editor routing remain pending. No current capability, matching revision or completed image bypasses `PreviewReadiness`.

## Deferred validation

All CPU/OpenGL sources compile with Java 25 and pinned LWJGL 3.4.1. No renderer test, GL context, shader or native visual comparison has run for this batch. At renderer completion, verify mixed native/JSON/empty/problem worlds, exact frozen visibility ownership and wrong-snapshot rejection, full configured height, native picking identity, every phase budget and old pinned-scene admission failure, source/assignment sharing and catalogue release, fatal diagnostics without partial outputs, cancellation during each phase, pending replacement, rejected submission, invalidation/take races and shutdown with transferred scene leases.

For ordering, cover source/scratch limits, native face identity, rapid pose/resize/FOV changes, same-wrapper rejection, latest completion ownership, setup failure, retry, failure/close races and release after cancellation. On real Windows GL contexts, cover CPU-to-GPU handoff, missing/native material inputs, tiny upload budgets, completion before/after camera changes, no stale frames, same-pose order reuse, failed frame replacement, retained image/picking lifetimes, hostile host state, original-context rejection and cleanup retry/fence/quarantine. Verify visible blocks/biomes against native output before enabling any supported preview. Native producers, total application admission and final integration remain pending.

## Supplied native visual producer edits

Before enqueueing a ready request, `NativeVisualInputs.capture` can apply explicit full native replacements or additional layers under the same borrowed source session. [WorldVisualEdits](VISUAL-EDITS.md) bounds complete cell traversal, choice interning, old/new source packets and assignment-copy peak; the native wrapper also admits the retained mask. The result preserves snapshot, offsets, tint input, scene/section/job limits and visibility identity. No producer callback or native handle reaches this CPU/GPU path, and native fidelity/readiness remain unverified.

## Shared memory coordinator

Opt into [RenderMemory](SHARED-MEMORY.md) with `WorldRenderWorker(workerBytes, memory)` and `GlDevice(limits, memory)`. One world account covers its job reservation and cache admission; completed/external scene leases keep the cache charge after transfer or worker close. The device separately admits staging and all source/GPU journals before allocation, conservatively including shared CPU scene data again. Shared rejection precedes world revision/cancellation changes and cache admission mutation; retirement/fence/quarantine retain charges. Other preparation workers and external native/CPU owners are not implicitly admitted by these constructors.

[Preparation-worker holds](WORKER-MEMORY.md) now extend the device coordinator to the connected map, atlas and camera-sort paths. Their tracked CPU completions must be closed; take transfers the hold rather than releasing its charge. The GPU views do this after destination admission/failure, with conservative overlap, while cancelled active work keeps an independent hold until unwinding. Lower-level consumers must retain equivalent ownership when keeping raster/plan/order data.

[Picking/standalone admission](PICKING-MEMORY.md) now covers optional index/source/query lifetimes and visual/scene worker ownership. A consuming visual-to-scene overload validates coordinator identity and admits the destination before releasing the visual completion hold. The managed standalone scene worker retains cache charges through external leases, supports immediate invalidation and transfers only an explicitly matching revision when requested. Caller-native/external ownership still needs host admission.

[Native capture ownership](NATIVE-CAPTURE.md) now admits full volume/model/visibility and map capture before native sampling, then seals detached `RenderInput` owners. The world worker consumes ready/model inputs after destination admission; the map worker and map view consume sample requests with the same coordinator check. Source holds survive preflight and consuming failures, while borrowed engine/session/RNG/provider references unwind before return. Native factories/fidelity and final editor integration remain pending.
