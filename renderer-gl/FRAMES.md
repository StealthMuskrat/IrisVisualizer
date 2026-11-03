# Completed frames and bounded scene replacement

Renderer implementation, 2026-11-03. All 76 main Java sources compile with Java 25 and pinned LWJGL 3.4.1: 55 CPU and 21 OpenGL. No frame, multi-epoch cache, cleanup/adoption, GL, concurrency, pixel or interactive Windows tests have run. Renderer tests remain deferred until implementation is complete; unsupported previews remain disabled.

## Replacing a scene while retaining its previous image

The section cache can now hold multiple exact native/atlas epochs within its existing aggregate ceilings. An epoch is the identity pair of `WorldModels` and `WorldVisibility`; section keys also include extent and section limits. Equal dimensions, seeds or hashes never mix different native choices, visibility masks or asset layouts.

Before changing admission, `prepare(scenePlan, cancelled)` checks the complete requested scene plus all pinned resident data. Matching resident sections in the same exact epoch are counted once. Every distinct pinned epoch and the requested epoch contributes its source payload once, conservatively even when some inputs are shared across epochs. Total sections, faces, metadata, mesh bytes, source/working bytes, ticket count and epoch count must fit together. A scene that cannot fit alone reports LIMIT; one that cannot coexist with protected data reports CACHE_BUSY. Rejection leaves existing admissions and owners intact.

Only after preflight does the cache commit the target epoch and evict eligible nonrequested unleased entries in LRU order until the complete prospective scene fits. Requested resident entries and all pinned/incomplete entries stay protected. Required metadata and the eviction iterator are allocated before commit; no section arrays are emitted by admission. Cancellation is checked through preflight and immediately before commit. The bounded commit itself finishes without cancellation or new metadata allocation, then normal scene assembly resumes cooperative cancellation.

`reserve` only accepts sections included in the currently prepared complete plan. The cache remains a single active assembly/admission stream, as used by `WorldSceneWorker`; retained prior results are independent readers. Preparing a different scene invalidates further admissions from the older plan without revoking its existing tickets. Completing/releasing an already-owned reservation remains safe. Failure during section assembly publishes no partial scene and releases its acquired tickets; valid unleased entries remain reusable/evictable.

There are at most 64 retained epochs and 65,536 section tickets. Existing default/hard section, face, marker and byte ceilings are unchanged and apply across all epochs. `cache.stats().epochs()` exposes resident/prepared epoch count. Source charge releases only when the epoch has no entries and is no longer prepared. Cache close stops admissions and removes unleased data; pinned data/source remains charged until final shared-owner release. This is bounded overlap, not unlimited history or a guarantee that two maximum-size scenes fit. If old and new data exceed the combined budget, report the limit without silently reducing generation area or height.

## A completed world image has its own owner

`GlFrame.render(renderer, revision, scene, atlas, order, camera, width, height, obsolete)` renders into a newly owned `GlTarget`. Nullable atlas/order select the original raster/no order, with existing translucent guards unchanged. All input GPU owners are borrowed for the duration of the call. The previous displayed target is never supplied or redrawn.

The factory checks device/scene/order identity, captures the exact atlas raster and an independently retained CPU scene owner, creates a new target, and runs the existing material/marker passes. Only successful drawing, host-state restoration and the final obsolete check produce a `GlFrame`. A validation, allocation, draw, restoration, cancellation or result-allocation failure retires the new resources and returns no frame. An independently displayed old frame remains untouched, including after resize failure.

The obsolete supplier must be side-effect free. It can observe an externally advanced edit/view token before allocation, before drawing, after drawing and before queue adoption. It cannot preempt a driver operation or an individual draw. Rendering still runs on the original context thread; this layer adds no GL worker, scheduler thread or hard frame-time guarantee.

`frame.info()` is detached metadata: unique frame UUID, application revision, generation receipt, camera, actual target dimensions, atlas clock and successful submission counts. It holds no scene graph. `info.ray(x,y)` uses the camera and dimensions that produced that particular image, rather than a newer pending camera. This receipt establishes a completed backend draw, not native generation parity or preview permission.

`textureId()` is borrowed while the frame owner is live. The target is private and cannot be redrawn through the frame API. `raster()` borrows the exact source atlas snapshot for alpha-aware picking, and `retainCpuScene()` returns an independent CPU lease for indexing. Callers keep a frame owner alive for the full lifetime of pending/active queries borrowing its animation raster.

`retain()` creates an independent frame wrapper sharing the same target, journal and payload, capped at 65,536 wrappers. Allocate a retained owner before handing a frame to a presenter or outstanding inspection operation; close it on the context thread after its last submitted use. Retaining does not allocate another texture or duplicate CPU/GPU byte reservations. After the final wrapper closes, the CPU journal releases and the target retires through the normal GPU fence/quarantine contract. A failed disposal that has not reached retirement retains its final closing owner for explicit retry. Device teardown still owns any quarantined journal.

Closing the original `GlScene` after a completed draw does not invalidate that image. The frame journal depends on the scene allocation and target, keeping their resource records and charges alive. It owns an independent CPU result wrapper for native inspection. The base scene's CPU charge remains in the dependency-pinned parent, so the frame does not charge that identical payload again. Changed animated CPU pages differing from the base raster are additionally charged in the frame journal. Separate frames conservatively charge their own changed-page references; independent wrappers of one frame share its single charge.

This deliberately retains source GPU resources while any completed frame still needs its source/inspection context. It trades bounded memory for a valid previous image and correct ownership. External picking indices and their CPU work remain part of application-wide admission. There is no assertion that the device's private ledger measures all application memory.

## Revision-checked presentation queue

`GlFrameQueue` owns at most one untaken completed frame. `invalidate(nextRevision)` advances to a newer application token and retires any queue-owned obsolete output. A frame already transferred to the presenter remains caller-owned.

`render(revision, request, obsolete)` accepts the latest waiting revision, borrows its scene/atlas/order/camera inputs, and synchronously produces a complete frame. Old/duplicate revisions do not render. Reentrant queue mutation from the obsolete supplier is rejected. Failure/cancellation publishes a failed/cancelled status with no partial image. The queue retains no request or original scene graph just to report status.

`take(latestRevision)` transfers READY exactly once, checking the queue token and frame receipt's own revision. The application rechecks its selection/native/view context immediately before presentation. Resize, camera, scene and animation changes all require a newer presentation token. Only after adopting the new frame should the presenter close its prior owner after the last image draw using it. Queue close affects only untaken queue-owned frames.

Scene, map and atlas upload queues now also require their appropriate active/READY phase before progressing or transferring results. A failed retirement can leave an older resource owned for retry; it must not be relabelled as a newer completion merely because the queue's revision has advanced. FAILED/STALE work cannot resume into a fresh READY result without a new accepted request. Context/fence failures continue to retain their journals and charges until cleanup succeeds.

## Numerical and lifecycle boundaries

Each completed world image uses one existing target allocation and one CPU-only frame journal. Target cost remains eight reserved GPU bytes per pixel (RGBA8 plus conservative depth24 storage). Frame wrappers add no target payload. Dimensions/pixels remain bounded by the configured/hardware `GlTarget` limits. Changed animation CPU pages are charged before target allocation. Scene/renderer/target dependencies remain under the existing 64-journal, 65,536-name and combined device ceilings; no limits/defaults were enlarged.

A presenter must close replaced frames. GPU-retired targets remain charged until fences complete; retaining many images or a stalled GPU can explicitly block the next frame. The API creates no backups, recovery files, persistent frame history or modified generated voxels. Save/Export, native readiness and unsupported preview gates are unchanged. Actual ImGui presentation, window resize/input routing, native producers and full application admission remain integration work.

## Deferred renderer-stage verification

At renderer completion, exercise two distinct exact epochs while old results, GPU scenes, picking indices and completed frames remain live. Verify combined source/mesh/ticket/metadata counts, same-epoch resident reuse without double charge, unleased LRU removal, all 64 epochs, zero-mesh epochs, complete-plan-only reservations and stale admission rejection. Reject pinned capacity before mutation, cancel preflight before commit, inject metadata/output failures, and confirm no partial scene or lost prior owner. Close caches/results in different orders and verify final source charges clear.

Render/resize from a displayed complete image while injecting invalid camera/order/atlas/device inputs, every target/storage/draw/restoration failure, obsolete token changes and result allocation failure. The old texture/pixels/receipt must stay valid. Check empty/marker-only and translucent scenes, exact dimensions/camera/atlas clock and statistics. Compare presented images and picking against their particular frame receipts rather than pending inputs.

Verify retained wrapper limits and exactly-once release, CPU/changed-page charge sharing, source retirement before image closure, dependency-pinned parents, GPU fence timeout/quarantine, retry after wrong context, multiple live frames and full device teardown. Retain an old image/atlas for a delayed pick, replace presentation, then close each owner without uncharging live data early.

Exercise queue invalidation during cleanup failure, ready-before-take, obsolete checks during rendering, repeated take, close/retry and delayed caller adoption. No failed old scene/map/atlas/frame may be delivered under a new revision. Profile synchronous target creation, complete draw, cache preflight/commit and retirement under Windows driver stalls. No runtime or native fidelity claim follows from Java compilation.
