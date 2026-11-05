# Current-source adoption and display failures

Renderer implementation, 2026-11-04. World, native-map, scalar-volume and original-image views now track successful current-source upload adoption separately from retained GPU owner references. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Code inspection and compilation only: failure injection, worker/context execution, native/pixel comparison and renderer-stage tests remain deferred until implementation is complete. Unsupported previews remain disabled.

## A retained cleanup owner cannot become a new source

The view controllers retain an owner when resource retirement fails, allowing later cleanup attempts while device journals preserve its charges. Source invalidation or accepted replacement can therefore advance the source revision while an old owner field remains nonnull. Previously, navigation inferred source availability from that field. It could change FAILED to WAITING_VIEW and permit attempts to draw an obsolete owner under the replacement revision. Without an owner, navigation could also retain FAILED while clearing its useful diagnostics.

Each controller now keeps a private current-upload adoption flag. It begins false, clears when invalidation or an admitted replacement advances source state, and becomes true only when the corresponding complete GPU upload is taken into that view. Closing clears it before cleanup. A CPU completion, queued upload, status value or surviving old owner cannot set it.

Map/image/scalar worker admission still precedes view mutation, so rejected CPU admission preserves the prior tokens, source availability and display owners. World submission retains its existing explicit world-revision invalidation and consuming-owner contract. The world invalidation status is now allocated before source/view revisions change, preserving the prior state if that allocation fails.

World-frame take also prepares its delivered status before transferring the queued frame. A status allocation failure therefore leaves the image owned by the queue instead of losing the transferred owner before returning it.

## Navigation and recovery

| Current source state | Camera/viewport/clip changes | Redraw and frame publication |
| --- | --- | --- |
| Waiting for input, CPU preparation or upload | Keep that source phase; store valid display settings where the existing API permits | Wait for complete current upload |
| FAILED before current upload adoption | Keep FAILED, structured problems, failure code and reason even if an old owner remains | Reject redraw; no obsolete draw or frame transfer |
| Current upload complete | Request a new display revision and retire any untaken old frame | Draw the current uploaded source when its other prerequisites are ready |
| Display failure with current upload complete | Permit the existing display retry route | Redraw can retry with that complete source |

Valid display changes retain their existing dimension, precision and no-op checks. Invalid arguments still fail before mutation. Where navigation is permitted during a source failure, it may advance the display revision, but it does not clear upstream diagnostics, retry CPU/GPU source work or confer source availability. A new accepted source request follows the existing resubmission/revision rules. [Explicit upload retry](UPLOAD-RETRY.md) can also reopen the failed current world source at the same world token after cleanup, advancing only its display revision before fresh CPU submission. `advance` and `take` both require current upload adoption before drawing or publishing an image.

`GlWorldView.redraw` now requires a complete current world upload and camera. Configuring a camera before upload remains supported through `view`; redraw cannot turn a pending/failed source into a drawable one. Pose changes still invalidate ordering, and complete current scenes retain their existing sort/display retry behavior. Atlas clocks are stored while waiting, but forwarding clock work, constructing animation and advancing its uploads require the current world upload. An animation owner retained after failed source cleanup cannot resume obsolete work.

`GlMapView.redraw` and `GlImageView.redraw` require the current adopted map/image and configured viewport. Map grid framing can still be configured while source work is pending; original-image framing retains its decoded/uploaded-dimension requirement. Matching-grid view preservation and original source pixels/values remain intact.

`GlNoiseView.redraw`, automatic camera fitting and the convenience recolor method require the current complete scalar upload. The explicit `recolor(source, colors, limits)` route remains available for caller-held surfaces during rapid replacements, under its existing exact-volume and geometry-policy guards and CPU admission. It does not use an unadopted scene left in the view's cleanup fields.

## Ownership and scope

These guards do not release or replace cleanup owners. Existing close/retry, fence/dependency/quarantine and shared-admission rules remain responsible for actual disposal. An unsuccessful close cannot make its resources free or transferable merely by clearing source availability. Independently transferred old frames remain caller-owned and keep their original image/source receipts; the view never relabels them as a new source revision.

No source-sized arrays, worker, GL resource, memory ceiling, generation setting or preview permission is added. Source adoption describes this view's completed upload only; native readiness, selected assets and final application validation remain separate requirements.

## Shutdown continues after worker failure

All four connected views now aggregate CPU shutdown errors alongside GPU cleanup errors. A worker close failure no longer skips later upload, frame or source disposal. World-view shutdown attempts both owned workers before closing frame/upload queues and scene owners; it does not need an animation revision increment just to tear down an exclusively owned worker. The first error is rethrown and later distinct errors are suppressed, including terminal-status publication failure. Successful terminal publication reports CLOSED even when cleanup failed; if that status allocation fails, the prior metadata snapshot may remain, while the closed/adoption guards still forbid further work.

| Owner | Cleanup attempted after CPU shutdown error |
| --- | --- |
| World view | Other worker, completed-frame queue, upload queue, animation, draw order and scene |
| Native-map view | Upload queue, untaken frame and map |
| Original-image view | Upload queue, untaken frame and image |
| Scalar view | Untaken frame, partial upload and scene |
| Standalone world animation | Atlas queue, prior retirement frame and current atlas |

World scene release also catches fallback animation-worker invalidation failure when no animation owner exists, then visits order and scene resources. This applies during replacement/recovery as well as shutdown. A failed retirement keeps its existing reachable owner or device-retired/quarantined charge; reaching CLOSED does not assert that driver fences or active CPU stacks have completed. Caller-transferred frames and borrowed renderers/devices keep their existing ownership.

A standalone `GlWorldAnimation` still borrows its exclusive worker. If invalidation fails, for example at revision exhaustion, shutdown retains the first error but continues closing atlas owners. It clears pending CPU adoption metadata only after invalidation succeeds or the borrowed worker is already CLOSED. The host may close that worker and repeat animation close to finish the incomplete cancellation step. This does not turn a borrowed worker into an owned one.

All twelve [CPU workers](../renderer/WORKER-MEMORY.md) now retry incomplete shutdown independently of their closed input gate, continue executor/cache cleanup as applicable after earlier errors and preserve caller-owned source/output charges. Controlled failure-injection groups compile but remain unexecuted.

Each view/animation marks disposal complete only after the close attempt, including status publication, succeeds. An unsuccessful attempt stays eligible for another close; unfinished GL resources remain reachable under their prior cleanup contracts. Context/thread validation still occurs before any teardown. Cancellation remains nonblocking and active worker inputs retain their admission until unwind. No memory ceiling, source identity, preview gate or GPU retirement rule changes.

At renderer completion, inject a worker close/invalidation error before each GPU cleanup stage and combine it with upload/frame/source retirement errors. Verify every remaining owner is visited, the first error remains primary, later errors are suppressed and no resource is re-adopted. Include world shutdown at maximum animation revision, scene release without an animation owner, standalone borrowed-worker exhaustion followed by host close/retry, closed-status allocation failure, retained independent frames and repeated cleanup failure. Compare active reservations, deferred/quarantined charges and final released ownership on the original Windows GL context. These failure paths are source-inspected and compiled only; none has been executed.

## View construction before worker ownership

Map and original-image views now construct their empty upload queues before creating their CPU workers. Worker creation is their final fallible setup step, so queue/status/context failure cannot strand a previously created worker. The scalar view already creates its worker last and keeps that ordering. Empty queues retain metadata and borrowed device/renderer references only; their constructors reserve no device journal, source lease, texture or other GPU name.

World-view construction likewise finishes empty upload/frame queue setup first. It then constructs the atlas worker with the caller's animation limit before creating the fixed-limit ordering worker. An invalid animation bound therefore creates no ordering worker. If ordering-worker construction fails, setup attempts to close the already-created atlas worker, keeps the original construction failure primary and suppresses any distinct rollback error. No later fallible setup remains after both workers are owned. Both inherit the same optional device coordinator as before.

This changes no public constructor, default limit, executor thread name, job admission or successful view behavior. Renderer/device ownership remains borrowed. Constructor rollback is best effort; a shutdown failure is reported as suppressed rather than silently claimed to have completed. No fake GL context or runtime test is used to validate these paths.

At renderer completion, inject RuntimeException and Error at queue/status/context setup, atlas-worker creation, ordering-worker creation and rollback shutdown/status publication. Check no worker exists before all empty queues succeed, invalid animation limits create no ordering worker, the original error remains primary and borrowed renderer/device journals and accounts remain unchanged. Include valid minimum/maximum worker limits and ordinary submission/close after successful construction. These cases require deferred Windows/GL failure injection; compilation and source inspection are the current evidence.

## Queue shutdown and terminal publication

The six context-owned queues (`GlUploadQueue`, `GlMapQueue`, `GlImageQueue`, `GlAtlasQueue`, `GlFrameQueue` and `GlReadbackQueue`) now distinguish a closed input gate from successfully completed disposal. Close validates the original live device, thread, GLFW context and LWJGL capabilities before changing queue state, including for an empty queue. The frame queue also rejects close from its rendering callback before mutation. Close queues before closing their device; a previously successful queue close remains an idempotent no-op after device teardown.

Once valid teardown begins, the input gate closes permanently. Cleanup is attempted first, then CLOSED publication is attempted independently. A cleanup error remains primary if status construction also fails; a distinct publication error is suppressed. Disposal becomes complete only when both steps succeed, so another close retries even if all owned resources were already released and only terminal metadata remains unfinished. Failed context validation leaves the previous gate, status, token and ownership unchanged.

CLOSED metadata contains no source, progress, image or diagnostic payload. In particular, atlas shutdown no longer reads progress from an upload whose cleanup just failed. If terminal allocation itself fails, the previous status snapshot may remain; the closed gate still rejects invalidate, retry, submit/render, advance and take. The caller may inspect status and repeat close, but cannot recover output through a failed shutdown.

| Queue | Queue-owned cleanup | Ownership kept independent |
| --- | --- | --- |
| World upload | Active upload and untaken scene, visiting both after an earlier cleanup error | Transferred GPU scenes and borrowed renderer/device |
| Native-map/original-image upload | Active upload and untaken map/image, visiting both after an earlier cleanup error | Transferred GPU sources and borrowed input values |
| Atlas update | Active patch upload | Displayed atlas, scene, worker and transferred atlas frame |
| World frame | Untaken completed frame | Source scene/atlas/order and transferred frames |
| Readback | Active transfer and untaken CPU pixels | Caller source frame and transferred raw/owned pixel packets |

Existing release helpers preserve reachable unfinished owners and hand retired/quarantined allocations to the device journal under the same accounting rules. Queue disposal does not wait for fences, close the borrowed device, collect journals or uncharge independently transferred CPU inputs.

At renderer completion, cover all six queues in empty, pending, READY and DELIVERED states as applicable. Inject RuntimeException and Error during each cleanup stage, terminal-status allocation alone and both failures together. Check primary/suppressed identity, repeated failure, successful retry after ownership has already cleared, terminal payload clearing, stable revision tokens and closed adoption guards. Reject wrong thread/context/capabilities before mutation; return to the original context and verify normal use or cleanup remains possible. Include close from the frame obsolete supplier, caller-transferred resources surviving queue close, close before device teardown and successful repeated close after device teardown. Verify retirement/quarantine charges until actual release on real Windows GL. These paths are source-inspected and compiled only; no GPU or failure-injection test ran.

## Deferred verification

At renderer completion, inject invalidation/upload/old-owner cleanup failures before and after source-token changes in all four views. Preserve the old owner deliberately, then change camera, viewport, clip, fit/pan/zoom where permitted, redraw, advance and take. Verify no old-source draw, atlas work or frame transfer occurs under replacement tokens, and source diagnostics remain available through navigation. Include no-op/invalid display changes, repeated failures and retrying close with live caller frames.

Check worker admission rejection before mutation, successful replacement after failure, stale/current take tokens, complete upload before viewport configuration, and allocation failure while preparing world invalidation/delivered status. Verify valid display-failure retry, transparent ordering and externally clocked animation with a complete current scene. Exercise scalar convenience versus explicit retained-source recoloring and source/geometry mismatch guards. Confirm old-frame pixels/receipts, exact native values and all retained/retired/shared charges until real cleanup completes. Runtime, real-driver/native comparison and final editor integration remain pending; no renderer-stage tests ran for this batch.

## Rejection before source acceptance

[World preflight](WORLD-PREFLIGHT.md) now distinguishes invalid prepared scene/material submission from a failure after accepted replacement. Invalid candidates preserve the prior world/view revisions, adoption flag, upstream diagnostics and still-owned ordering/animation/images. Fresh candidate wrappers are consumed; independent old owners remain live. Explicit upstream invalidation and accepted-source failure/retry retain their existing semantics. No runtime/GPU checks have been executed.

## Rejection before frame acceptance

[Frame queue preflight](FRAME-PREFLIGHT.md) now preserves earlier untaken world images when a newer render request fails scene/order/atlas, camera or target checks. It accepts no new token and invokes no obsolete callback. Explicit view invalidation and failures after accepted render retain their existing lifecycle; already-transferred images remain independent. No runtime/GPU checks have been executed.
