# Background world inspection handoff

Renderer implementation, 2026-11-04. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Compilation and source inspection only: no index/query/worker execution, concurrency tests, native/GPU comparison, GL context or workflow ran. Renderer-stage runtime validation remains deferred until implementation is complete. Unsupported previews remain disabled.

## Background index preparation

`WorldPickIndexWorker` owns one active preparation and one latest pending scene. It builds the existing complete section hierarchy and face/marker ranges on one daemon CPU executor. Submission reads scene usage metadata only; section counting, emitted-vertex scanning and hierarchy/range construction stay off the context thread. Native sampling, mesh generation, GL calls and pack writes never run on this executor.

`submit(freshCpuOwner, limits)` consumes one independently retained `WorldScene.Result` on success and rejection. Reusing a wrapper already held by this worker is rejected without closing its existing ownership. Always use a fresh `retain()`; submission does not grant permission to reuse a consumed wrapper after completion transfer. Failed/closed scenes, closed workers and local/shared admission errors preserve earlier jobs, untaken output and revision while closing only the fresh rejected owner.

Accepted replacements advance the revision, cancel active work cooperatively, release obsolete pending input and close untaken completed indices. Invalidation can do this before replacement input exists. Only the latest revision publishes READY; `take(expectedRevision)` transfers one `Completion`, whose `index()` owns the exact CPU scene lease and its admission. Closing that completion closes the index. A transferred index survives worker replacement, invalidation and shutdown; the host must eventually close it. Metadata-only status carries snapshot UUID and diagnostics, not arrays.

## Admission and unwind

Before queueing, a job reserves the lesser of its declared combined preparation allowance and source payload plus the declared index allowance. A source already exceeding the combined allowance is rejected immediately. This upper bound needs no face/section traversal. Actual counting on the executor still checks complete hierarchy/range/scratch bytes against the original 16 MiB index and 512 MiB combined ceilings before allocation; the admitted upper bound cannot authorize a larger build.

The worker defaults to a lowerable 1 GiB active-plus-pending ceiling. With `RenderMemory`, idle pending/untaken output can supply atomic replacement credit. A live active stack or independently retained owner cannot. Reservation rejection leaves existing work unchanged. Cache source ownership remains independently charged under its existing policy; this is conservative accounting, not graph deduplication or a total-process heap measurement.

Preparation receives a retained handle to the worker's already-admitted hold, rather than opening a second full-source account. It verifies the exact coordinator and sufficient admitted bytes before allocating. The worker retains its original handle until preparation/publication frames unwind. Only then does it shrink the shared charge to actual source-plus-retained-index bytes and release its handle. Thus a completion taken/closed before unwind cannot release active scratch admission early. Failed/cancelled jobs close source/index owners and release their reservation after execution returns.

`reservedBytes()` includes active/pending reservations and untaken output, without double-counting READY output still covered by its active job. Taken owners are external. Closing is idempotent/nonblocking, cancels work and drops worker-owned output; an active cancelled build remains charged until unwind. No source arrays or old jobs remain in executor idle locals. Existing index close still defers release through in-flight queries. Releasing old pending/completed owners can synchronously release bounded cache-ticket lists; submission and close do not promise a hard elapsed-time budget.

## Completed-frame capture

`GlFrame.submitPickIndex(indexWorker, limits)` must run on the original context. It checks the device/worker coordinator and retains a fresh CPU scene owner for the consuming builder. No hierarchy or vertex walk occurs in this helper. The frame can close after submission; the builder/index owns its independent CPU lease. It never retains a GL frame, target, texture, renderer or device for off-thread use.

Once a complete index is available, `frame.submitPick(pointerWorker, index, x, y, options)` checks:

- The frame, index and query worker use the exact same optional memory coordinator.
- The index refers to the exact CPU scene object retained by that frame, beyond merely matching generation/source/volume metadata.
- The ray comes from that frame's camera/viewport, and its captured geometric clip replaces the caller's clip while preserving other options, including range-culling mode.
- Displayed-alpha queries receive that frame's exact immutable atlas raster, including animation/interpolation state, and a detached image UUID/display-revision token.

All checks precede pointer revision changes. Geometry indices can be reused across camera, resize, clipping and atlas-only animation frames of the same CPU scene. New visual/mesh scenes need another index even when the native source metadata matches. The host decides when to request/reuse indices; no automatic frame polling or index cache is introduced.

## Pointer adoption and frame lifetime

`WorldPickWorker.Request`, Status and Completion now optionally carry `Frame(UUID, revision)`. Earlier constructors retain their unbound semantics. `take(pointerRevision, expectedFrame)` requires both the latest pointer revision and exact image token; mismatch does not consume output. `GlFrame.Info.pickFrame()` supplies that token, and `Info.matches(completion)` also checks source and clip. The detached receipt remains usable after frame closure.

On the context thread, with matching workers and after the corresponding READY status:

```java
long buildRevision = displayed.submitPickIndex(indices);
// Later, after this build reaches READY:
var indexOwner = indices.take(buildRevision).orElseThrow();
long pointerRevision = displayed.submitPick(picks, indexOwner.index(),
        pixelX + 0.5, pixelY + 0.5, WorldPickIndex.Options.DEFAULT);
var imageReceipt = displayed.info();
// Later, while this receipt is still the displayed image:
var result = picks.take(pointerRevision, imageReceipt.pickFrame())
        .filter(imageReceipt::matches);
```

The half-pixel offsets apply to integer top-left viewport pixel indices. The host must compare with its currently displayed image and latest pointer; an old receipt matching its own old query is insufficient. Invalidate pointer work when the pointer leaves or presentation changes. To retire inspection, invalidate pending queries and close `indexOwner`; active index queries cancel and retain their source through unwind. Camera/atlas changes do not automatically invalidate a separate caller-owned picker.

The pointer worker borrows caller-owned indices and never closes them. Keep the index live for pending/active work. After an accepted frame helper submission, the request independently retains/admit-charges its CPU raster, so the GL frame may close without waiting. Manual borrowed-raster requests retain their existing external lifetime obligations. Completed hits/statuses contain no index, atlas or scene arrays.

Pointer requests now enforce a lowerable 1 GiB active-plus-pending source ceiling even without a shared coordinator. Full index/scene/displayed-page demand is estimated before changing work; closed indices and foreign rasters reject consistently before publication. Managed requests also reserve through the existing coordinator, retaining cancelled active charges until unwind. Legacy constructors keep their signatures and acquire this explicit aggregate bound. No generation/preview default or device ceiling is raised.

## Deferred stage verification

At renderer completion, exercise index metadata admission versus exact construction at all section/range/index/combined bounds, failed/closed scenes, zero drawable output, stale completion, same-wrapper misuse, consumed-owner rejection and single delivery. Inject failures in scheduling, construction, hold retention and publication. Verify active+pending and READY replacement credits, take/close before unwind, source/coordinator mismatch, cancellation, same-worker retry, shutdown and final zero charge with delivered indices outliving their builder.

For frame queries, cover exact-scene rejection despite equal native metadata, geometry reuse over changed cameras/clips/viewport/atlas clocks, displayed alpha holes/interpolation and independent GL closure after acceptance. Compare rays/options and native hits against direct queries, including range-culling false propagation. Check same revision with different image UUIDs, mismatched/stale/duplicate takes, old constructors, error tokens, pointer invalidation and closed indices. Confirm detached receipts/results hold no large arrays or GL owners, and no CPU job calls GL. Runtime/performance/native/GPU verification and final editor adoption remain pending.
