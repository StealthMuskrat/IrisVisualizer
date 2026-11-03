# Immutable GPU atlas animation frames

Renderer implementation, 2026-11-03. All 59 Java sources compile: 45 in the CPU library and 14 in the LWJGL backend. No GL context, framebuffer copy, animation upload, fence/dependency, timing or pixel-comparison test was executed for this batch. Tests remain deferred until renderer implementation is complete. Unsupported previews remain disabled.

## CPU preparation and exact baselines

Capture an immutable GPU atlas view with `GlAtlasFrame.capture(scene)` on the scene's original context thread. It borrows the initial texture names through an owned frame journal, retains the original CPU raster and pins the base scene allocation. Capture the frame's `raster()` on that thread before sending it to a CPU worker; frame methods themselves remain context-affine.

On a worker, the existing `AtlasImages.updates(raster, tick, partialTick, cancelled)` creates supported scheduled-frame patches. `AtlasFramePlan.prepare(raster, update, cancelled)` uses the existing validated CPU copy-on-write application, groups patches by page and records the exact expected/updated rasters, changed page indices, copied page bytes and patch bytes. Unchanged CPU pages are shared. Existing native animation-layout/interpolation guards, patch limits, cancellation and CPU peak checks still apply. Failure exposes no partial new raster or page list.

The plan is immutable caller-owned data. It does not run a timer, change native state/biome data or declare native visual parity. Its expected raster must be the exact raster of the displayed GPU frame; matching tick, layout or archive hash alone cannot replace that identity check. A failed or obsolete plan must be recomputed against the actual current frame.

## Shared pages and independent lifetime

`GlAtlasFrame` owns a snapshot of page references and a small allocation journal. Initial pages remain part of the base `GlScene` allocation. Replacement pages own separate texture/CPU-page allocations, and unchanged pages share those allocations between snapshots. Closing a snapshot releases its page owners; a replacement texture retires only after its last page owner releases it. Old snapshots are not retained as a chain of complete historical atlases.

The base scene remains required for geometry, original pages, CPU generation provenance and layout. Closing it prevents further rendering/updating even if dependent frames keep its physical resources charged. Close frame/job owners before final device teardown. The original scene allocation still retains its initial atlas; this implementation does not split that initial allocation or falsely count those original pages as free.

Device dependencies now form a bounded acyclic graph rather than a single-parent chain. Frame journals pin their base scene and replacement-page allocations. A temporary read framebuffer pins a retained source-frame journal. This preserves texture and CPU-page charges even if attachment cleanup fails or a source view is closed while an upload is paused. Collection and explicit teardown release children before all parents. The existing allocation count bounds the graph, and cycles are rejected.

The device initially reserves copied CPU page bytes plus patch payload for an update. As a replacement page allocation is created, its already-reserved CPU charge moves from the work journal to that page without changing the aggregate allowance. That page allocation retains its immutable RGBA image until actual resource release. Work/source raster references are cleared after transfer; individual page owners preserve only the current page data they need.

## Copy-and-patch upload

`GlAtlasUpload.begin(displayedFrame, plan)` borrows the caller's displayed frame and retains an independent source snapshot. It consumes neither caller input. Device admission checks extra CPU payload before owning the plan. The caller may retain its displayed frame for rendering throughout the update; only new private textures are written.

For each changed page, the uploader allocates one fresh RGBA8 texture, copies the source GPU page through an owned read framebuffer, then uploads the plan's padded sprite patches. Copy and patch transfers use whole rows, at most 64 KiB per work action. Source attachment is detached after each copy action, including failure paths; dependency ownership also protects it until temporary framebuffer deletion. No source texture, original scene geometry, material declaration or generated block/biome data is overwritten.

`step(GlUploadBudget, cancelled)` shares the existing step budget model. The transfer ceiling applies to **GPU copy bytes plus client patch-upload bytes together**, with separate progress counters for each. Action/work ceilings and a cooperative elapsed target also apply. Storage bytes are reported separately. Host state is restored on every yield/failure; as with full uploads, driver calls, allocation, cleanup and restoration cannot be preempted or guaranteed to meet a hard frame duration. Cancellation predicates must be non-mutating checks.

Completion verifies page count, copied bytes, uploaded patch bytes and remaining CPU scratch charge. A new GPU frame is constructed only after all private textures and patches are complete and host state is restored. No-change plans create a new clock/token snapshot sharing every page, without texture copy or patch data transfer. No geometry or marker buffers are uploaded again.

`take(stillDisplayedFrame)` transfers the result exactly once and rechecks the exact original frame-view token. A changed or closed baseline cannot adopt it. Cancelled/failed work retires only newly owned resources, while the caller's displayed frame remains untouched. Submitted copy/upload resources and source attachments retain the existing fence/quarantine rules; cleanup failure does not make their memory charges disappear.

## Revision-checked queue and drawing

`GlAtlasQueue(scene, sceneRevision)` owns at most one active/ready atlas update for that fixed GPU scene. It never owns or closes the caller's displayed frame. `submit(sceneRevision, frameRevision, displayedFrame, plan)` accepts a fresh frame request, while `invalidate` can discard obsolete queue-owned work before a new plan arrives. Older scene/frame observations do not disturb newer work; a newer scene revision permanently invalidates this queue.

`advance(budget, latestSceneRevision, latestFrameRevision)` runs one matching upload step. `take(latestSceneRevision, latestFrameRevision, displayedFrame)` checks both revisions and the exact displayed baseline before returning an `AutoCloseable` completion. The caller owns the new frame after transfer, chooses the actual adoption point, and closes the previous frame after its last submitted use. Queue failure, supersession and shutdown never close that previous caller-owned frame. Ready status is GPU upload completion, not permission to show an unsupported native-world preview.

Draw an animated snapshot with `renderer.render(scene, frame, target, camera)`. The frame must belong to that exact GPU scene and layout. The renderer validates it once and uses its immutable texture-name table for the original indexed draw ranges. Geometry, alpha candidate ranges and marker provenance stay unchanged; the existing no-frame overload still draws the original scene raster. Native tint inputs now travel with scene uploads; translucent scenes require the camera-bound plan overload `renderer.render(scene, frame, plan, target, camera)`. See [MATERIALS.md](MATERIALS.md). Native multi-frame interpolation and mipmap/filter limitations remain in force.

Status snapshots are immutable/volatile for polling. Mutation, frame ownership and GL work belong to the original context thread. Plans prepared off-thread must return through the application's revision/adoption ordering; the queue does not schedule native generation or introduce an unbounded pending-plan list.

## Bounds and remaining verification

No existing size/default is increased. CPU preparation retains the existing 32 MiB patch, 128 MiB base-atlas and 512 MiB CPU atlas working ceilings. The GPU device still bounds active/retired payload to 512 MiB, known CPU/GPU/staging payload to 1 GiB, journals to 64, names to 65,536 and staging to at most 4 MiB. Every frame, retained source lease, work journal, temporary framebuffer and replacement page consumes a journal slot; changed CPU pages/patch bytes and full replacement GPU pages remain charged while owned. Resource pressure rejects the update rather than mutating or reducing the displayed frame.

Caller-held plans/escaped raster references, object/collection overhead, other caches, driver storage overhead, native generation and unrelated application/GPU resources remain outside primitive device accounting. These APIs do not confirm total application memory or bypass preview readiness. Frequent updates require periodic `device.collect()`; dependencies may take multiple nonblocking collection passes to retire.

At renderer completion, compare every incremental GPU page with the prepared CPU raster, including nonzero patch offsets, odd/padded rows, gutters, mixed changed/unchanged pages, repeated frames, cycle skips, clock seeking and no-change updates. Verify framebuffer copy orientation, RGBA/alpha preservation, nearest/base-level sampling and host read-FBO/read-buffer/texture/unpack state across every yield. Include failed attachment/detachment, copy/upload errors, cancellation and failed restoration; the displayed source textures must never change.

Exercise exact scene/layout/raster/token/revision mismatches, stale CPU plans, scene closure and ready-before-adopt races. Verify unchanged-page sharing, independent replaced-page retirement, no historical-frame chain, CPU charge transfer without double release, source FBO dependency retention, graph cycle rejection, budget pressure, duplicate close and context/device teardown. Shader/native interpolation, native tint/transparent pixel parity, filtered mipmaps, real GPU profiling and editor integration remain unfinished. No renderer tests were executed for this batch.
