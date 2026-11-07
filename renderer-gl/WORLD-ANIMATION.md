# World-view atlas animation

`GlWorldView` now connects the existing CPU atlas patch/copy path and immutable GPU atlas updates to completed world images. Animation uses an external clock and one bounded CPU worker reused across world replacements. This is source implementation with compilation only; native pixel, concurrency and real-driver validation remain deferred until renderer completion. Unsupported previews remain disabled.

## Clock and presentation

Call `view.clock(nonnegativeTick, partialTick)` for explicit seeks, or `view.playbackClock(nonnegativeTick, partialTick)` for a continuously advancing host clock, on the view's original context thread. Partial ticks must be finite and in `[0,1)`. The clock can pause, jump forward, seek backward or be set before the first scene arrives. Without an explicit clock, each newly uploaded scene keeps its initial raster clock. No internal timer, native engine call, world generation or geometry bake runs.

`advance(uploadBudget, latestWorldRevision)` observes the desired clock, requests CPU preparation, pumps a bounded GPU atlas update and renders a new image only after a complete immutable atlas is adopted. It never steps both the full-scene uploader and animated-atlas uploader with the same caller budget in one advance. Atlas adoption advances the view revision and requests a redraw; it keeps the camera-bound face order because animation does not move geometry. Camera navigation can continue drawing the current complete atlas while its replacement is prepared.

`take(worldRevision, viewRevision)` retains its exact-token contract. Read the latest view revision after advancing; an animated image can replace an untaken older image at the same world revision. Already transferred images keep their original texture, CPU atlas, clock receipt and picking input through later animation, resize, seek, world replacement and failed updates. No displayed source texture or completed target is overwritten.

The desired clock and displayed raster clock are distinct. Clocks selecting the same source frames do not allocate patches, upload pages or redraw. Consecutive requests selecting the same pending pixels share the in-flight preparation, including repeated schedule entries. The displayed clock receipt remains the clock used to build those pixels; it is not rewritten to imply a new render. An explicit `clock` seek back to the currently displayed pixels cancels the obsolete pending update. Live `playbackClock` changes instead finish an admitted sample before sampling the latest clock; see [bounded playback](LIVE-PLAYBACK.md). No intermediate clock history or extra preparation queue is retained.

The path now supports stepped schedules and [tick-quantized RGBA interpolation](../renderer/INTERPOLATION.md). Partial-tick changes alone do not change interpolated pixels. Existing metadata guards remain; native driver rounding, filtering/mipmap parity and native clocks remain pending.

## CPU preparation

`AtlasFrameWorker.Request.capture(raster, tick, partialTick)` performs a metadata-only preflight. It visits at most the bounded sprite palette and binary-searches each schedule; it neither decodes nor copies pixels. It records the exact expected raster, desired canonical tile/progress map, changed sprite count, full padded patch bytes and one page-copy charge per changed page. It checks the existing patch and atlas working ceilings before any pixel allocation.

The worker owns one active operation and one replaceable pending request. `submit` checks both reservations before changing revision, cancelling earlier work or discarding a completion. Rejection leaves those previous values intact. Obsolete active inputs stay charged until their stack unwinds. Preparation runs `AtlasImages.updates` and `AtlasFramePlan.prepare` on one daemon thread; patching, gutters and page copies occur there, followed by an exact preflight/output byte check. A successful plan transfers once through `take(revision)`; failure publishes bounded source diagnostics without a partial plan. Status contains receipts and clocks, not raster or native graphs.

`invalidate()` immediately invalidates untaken completion/pending work and cooperatively cancels the active operation. `close()` does the same without blocking the context thread. The active immutable source remains accounted by `reservedBytes()` until it unwinds. Superseded seek clocks, old worlds and closing views do not create extra worker threads. Live playback ticks only replace the two desired clock scalars until the admitted update completes. A standalone `GlWorldAnimation` borrows an exclusive worker; its host must not submit unrelated jobs to that worker concurrently.

## GPU handoff and cleanup

`GlWorldAnimation` keeps a private current `GlAtlasFrame` and `GlAtlasQueue` for the exact GPU scene. CPU plans must target that frame's exact raster object. Scene/frame revisions and the queue's source-frame token reject obsolete work. Only a fully uploaded frame replaces the current frame; unchanged pages are shared and replaced pages follow the existing dependency/fence retirement contract. CPU plans, source pages and replacement pages remain charged through the existing GPU allocation journals.

[Atlas adoption cleanup](UPLOAD-RETRY.md) now stores one prior frame owner through retirement before CURRENT status publication. Later advances and close retry any still-open retirement owner before further adoption; logically consumed frames keep deferred/quarantined device charges. Status allocation failure cannot strand the previous atlas in an unreachable local variable.

World invalidation cancels the CPU request, closes animation upload/current-frame owners and then releases scene/order owners. View shutdown closes its owned sorting/animation workers before disposing GPU owners. [Shutdown aggregation](SOURCE-FAILURES.md) visits the remaining resources even if a worker close or borrowed-worker invalidation fails, preserving the first error and suppressing later cleanup/status errors. Cleanup failures remain visible and retryable; a logically closed view is not permission to bypass the device's retirement/quarantine accounting. Caller-transferred images remain caller-owned.

CPU preparation failures appear in `animationStatus()` with model/asset diagnostics; the current complete atlas and camera rendering remain available. A GPU/cleanup exception also fails the enclosing view. Supply another clock (or the same failed clock) through either clock entry point to retry animation; after an enclosing view failure, call `redraw()` before advancing. A failure never permits partial atlas adoption. `animationUploadStatus()` exposes incremental GPU progress. `animationWorkerStatus()` and `animationReservedBytes()` expose worker state and still-owned CPU reservations for final application admission.

## Admission and limits

| Resource | Bound |
| --- | --- |
| Active CPU preparation | One job |
| Pending CPU clock | One latest request |
| Per-request primitives | Exact retained atlas input plus prospective padded patches and changed-page copies |
| Atlas patch output | Existing 32 MiB maximum |
| Atlas page storage | Existing 128 MiB maximum per raster |
| Per-request atlas working payload | Existing 512 MiB maximum; lowerable |
| Active/pending/untaken CPU worker reservation | 1 GiB default/hard; lowerable through the view constructor |
| GPU upload per view advance | One scene or atlas upload step under the caller's transfer/action/work/time limits |

Worker reservations include atlas source RGBA, mesh payload, one current page set and prospective output arrays. A ready plan shares its active job's charge while that job unwinds; after unwinding, the worker charges the untaken completion. A transferred plan becomes an external owner and is admitted again by the GPU updater before upload. Independent worker/device reservations may conservatively overlap during transfer. No CPU or GPU bound is increased.

The worker and device enforce their own ceilings. Their concurrent payload, the scene/cache, camera sorting, caller-held completed images, native jobs and other views still require final application admission. Object headers, metadata/maps, parser scratch and driver overhead remain outside primitive totals. Memory pressure fails explicitly rather than reducing area, height, fidelity or native data.

## Deferred stage validation

All 90 independent CPU/OpenGL sources compile with Java 25 and LWJGL 3.4.1. The eight native adapters are unchanged and still await genuine Windows native compilation and runtime validation. No renderer test, GL context, animation execution, native query or workflow ran for this batch.

At renderer completion, cover repeated frames, unequal frame durations, large integer ticks, pause/resume, forward/backward/cycle seeks, fractional clocks, all-static and empty atlases, no-change updates and exact preflight/copy payload equality. Verify one active/latest pending request, admission rejection without mutation, cancelled-active reservations, completion/take/close races, setup/allocation failure and immutable source pages. Exercise mixed RGBA interpolation, within-frame changes, equal quantized progress, repeated tiles, endpoint canonicalization and partial-tick invariance without silently falling back to stepped pixels.

On Windows GL, compare incremental textures and completed images to CPU rasters across tiny upload budgets, changed/unchanged page sharing and all copy/patch boundaries. Verify clocks before scene upload, seeks during CPU/GPU work, cancellation back to current pixels, stale world/view/raster/frame tokens, same-pixel coalescing, animation without a camera, camera navigation while uploading, unchanged sort ownership and one upload budget per advance. Retain old images and confirm their pixels, atlas alpha picking, state/biome identity and clock receipts survive successful and failed replacements. Cover CPU diagnostics, GPU/fence/cleanup failures, explicit retries, world churn, nonblocking shutdown and original-context/device teardown. Native visual parity and final editor integration remain pending.

## Independently owned CPU plans

[Owned plan handoff](../renderer/ANIMATION-OWNERS.md) now retains an independent completion input through GPU queue admission. Tracked worker output must share the device coordinator; supplied wrappers are consumed on every outcome, while other retained owners remain live. Ordinary exact-baseline/token validation and externally clocked scheduling are unchanged. Eleven synthetic animation groups are compiled but unexecuted; runtime/GPU checks remain deferred.

## Worker preflight before construction

[Destination validation](../renderer/ANIMATION-OWNERS.md#worker-validation-before-atlas-capture) now checks the borrowed worker before the initial atlas capture. Closed/exhausted workers and tracked coordinator mismatches fail without new atlas journals, page references or scene dependencies, and without cancelling queued/ready CPU work. Matching tracked and explicit untracked admission remain supported; cancelled active work may continue unwinding during world replacement. This snapshot does not claim worker exclusivity or reserve later job memory. Full and dependency-free compilation passed; the expanded retained-completion contract remains one of 11 animation groups (64 overall), all unexecuted.
