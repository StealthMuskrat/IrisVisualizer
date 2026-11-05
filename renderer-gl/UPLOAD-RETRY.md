# Explicit upload retry and atlas owner cleanup

Renderer implementation, 2026-11-04. Failed world, map and original-image uploads can now be explicitly reopened at their current request revision. The connected world view can accept a fresh retained CPU scene owner at the same native-world token after cleanup. Atlas adoption also keeps the previous frame owner reachable through retirement. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Runtime, failure-injection, native and real-driver checks remain deferred until renderer implementation is complete; unsupported previews remain disabled.

## Queue retry contract

`GlUploadQueue`, `GlMapQueue` and `GlImageQueue` expose `retry(expectedRevision)` on their original context thread. It accepts only the exact positive current revision while the queue is FAILED. Stale/future tokens and other phases return false without changing work, tokens or ownership. Invalid nonpositive tokens and closed/wrong-context use retain their existing explicit errors.

A retry prepares its WAITING status before cleanup, then closes remaining queue-owned partial/untaken work through the existing disposal path. Only successful cleanup publishes WAITING, at the same revision. A cleanup exception keeps FAILED and exposes the actual bounded failure reason; unfinished owners remain available to the queue's later retry/close path. Retired or quarantined allocations stay charged under the device's existing rules, even after their logical owner is consumed.

After a true result, submit complete retained CPU input again with the same revision. The normal complete upload restarts from its initial phase and retains all existing source/material, hardware, memory and transfer checks. Retry itself retains no replacement input and performs no native generation, image decoding or map colorization. It creates no timer or automatic retry loop.

`GlUpload.close()` also retries disposal when a prior CLOSED phase still owns its allocation. A terminal phase alone no longer causes world-queue cleanup to drop an unfinished upload owner. Once taken or actually relinquished to device retirement, repeated close remains a no-op.

| Endpoint | Input submitted after retry |
| --- | --- |
| `GlUploadQueue` | Fresh `WorldScene.Result` wrapper, with optional tint/lighting for that exact scene |
| `GlMapQueue` | Successful retained `MapRaster` |
| `GlImageQueue` | Successful retained decoded `SourceImage` |

World uploads still consume fresh wrappers on all outcomes; already-device-owned wrappers remain protected. Keep an independent scene owner before the first consuming submission, then transfer a fresh `retain()` wrapper for each attempt. Close that independent owner when retries are no longer needed. Its cache/source charge can block other work until released. Raw map/image data likewise needs its existing caller or admitted ownership through destination admission. A retry does not make escaped CPU references free.

## Connected world recovery

Call `GlWorldView.retryUpload(expectedWorldRevision)` only after a failed source upload/adoption. It returns false for a mismatched token, a nonfailed view or an already adopted current source; display failures with a current complete scene continue to use `redraw()`.

An accepted retry advances the display revision while preserving the world revision, camera, viewport, clip and externally requested atlas clock. It invalidates old ordering/untaken images, releases view-owned scene/animation/order resources and reopens its current upload queue. If earlier invalidation failed before that queue observed the world token, retry first brings the queue to the current token. Partial queued work is explicitly abandoned before reopening. Any cleanup failure leaves the view FAILED and its current-source adoption guard false.

Successful preparation returns WAITING_CPU. Submit a fresh retained scene wrapper using the existing `submit` or `submitLit` overload at that same world revision, then advance normally. No CPU scene or native sampling is started by retry; the host supplies already prepared current data. Current upload adoption remains false until the complete replacement is actually taken. Navigation cannot revive cleanup-retained old data during this process, and previously transferred frames keep their own original pixels/receipts.

## Atlas adoption ownership

`GlWorldAnimation` previously held its old atlas only in a local variable after taking a complete replacement. An allocation failure while publishing CURRENT could bypass the subsequent close, leaving the old journal/page owners unreleased.

Adoption now stores that previous owner in one bounded retirement field before replacing the current frame. Retirement runs before CURRENT status publication. Later advances retry any still-owned prior frame before clock scheduling or another adoption; shutdown also visits this owner alongside queued/current resources. A logical close consumes the wrapper while its device journals continue to own deferred/quarantined charges. A close that leaves the wrapper open keeps it reachable for cleanup.

At most one previous atlas owner occupies this field, so failed cleanup cannot build an unbounded owner list. The new complete frame also remains held if status publication fails. Existing exact-baseline/token checks, immutable page sharing, externally clocked retry and one scene-or-atlas upload budget per world advance remain intact. No GPU image, source primitive array or memory ceiling is added by the retirement field.

## Deferred verification

At renderer completion, inject initial/partial/completion upload and cleanup failures into all three queues. Exercise current/stale/future/invalid retry tokens, repeated calls, WAITING/UPLOADING/READY/DELIVERED/CLOSED phases, status-allocation failure before cleanup, failed cleanup preserving ownership, same-revision resubmission and exact material/source guards. Verify caller-transferred owners remain independent and every retired/quarantined byte stays charged.

For the world view, cover failures before its upload queue observed the token, partial upload during a display-operation failure, repeated cleanup failures, retained CPU scene submission without regeneration, display-counter exhaustion and current-source-only draw/take. Check camera/clip/clock preservation, old frames and rejection of upload retry for a complete current source. Inject atlas status allocation and prior-frame close failures around adoption, then repeat clock/redraw/advance/close; verify current and old page owners stay reachable or device-owned, one retirement slot, no obsolete baseline adoption and final charges/leases. Real-driver/native comparison and final editor integration remain pending; no renderer-stage tests ran for this batch.

## Scalar view recovery

The connected scalar view now also provides [same-source upload recovery](SCALAR-RECOVERY.md), accepting a complete retained surface rather than repeating CPU preparation. It verifies exact volume/full display style and retained output limits, preserves camera/clipping and retains cleanup ownership through failure. Independently admitted CPU surface inputs support consuming retry/recolor calls after frame closure. Runtime/failure-injection/GPU checks remain deferred.

## Connected map and image recovery

[Retained-raster view retries](RASTER-RECOVERY.md) now preserve native-map/original-image source tokens while checking latest capture/style or successful decode receipt. Scoped queue restarts abandon partial work after view-side failures; public retry remains FAILED-only. Independent CPU source inputs survive GL closure and remain charged through destination admission. Runtime/failure-injection/GPU checks remain deferred.

## Prepared world input rejection

[World scene/material preflight](WORLD-PREFLIGHT.md) now rejects incomplete or mismatched CPU inputs before discarding prior queue/view work. This rejection accepts no new token and leaves existing WAITING/FAILED state unchanged; corrected complete data uses its ordinary submission contract. Existing upload retry remains for an accepted source failure, with retained CPU ownership and cleanup checks intact. Runtime/failure-injection/GPU checks remain deferred.
