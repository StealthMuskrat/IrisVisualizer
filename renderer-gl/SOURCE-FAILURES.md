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

## Deferred verification

At renderer completion, inject invalidation/upload/old-owner cleanup failures before and after source-token changes in all four views. Preserve the old owner deliberately, then change camera, viewport, clip, fit/pan/zoom where permitted, redraw, advance and take. Verify no old-source draw, atlas work or frame transfer occurs under replacement tokens, and source diagnostics remain available through navigation. Include no-op/invalid display changes, repeated failures and retrying close with live caller frames.

Check worker admission rejection before mutation, successful replacement after failure, stale/current take tokens, complete upload before viewport configuration, and allocation failure while preparing world invalidation/delivered status. Verify valid display-failure retry, transparent ordering and externally clocked animation with a complete current scene. Exercise scalar convenience versus explicit retained-source recoloring and source/geometry mismatch guards. Confirm old-frame pixels/receipts, exact native values and all retained/retired/shared charges until real cleanup completes. Runtime, real-driver/native comparison and final editor integration remain pending; no renderer-stage tests ran for this batch.

## Rejection before source acceptance

[World preflight](WORLD-PREFLIGHT.md) now distinguishes invalid prepared scene/material submission from a failure after accepted replacement. Invalid candidates preserve the prior world/view revisions, adoption flag, upstream diagnostics and still-owned ordering/animation/images. Fresh candidate wrappers are consumed; independent old owners remain live. Explicit upstream invalidation and accepted-source failure/retry retain their existing semantics. No runtime/GPU checks have been executed.

## Rejection before frame acceptance

[Frame queue preflight](FRAME-PREFLIGHT.md) now preserves earlier untaken world images when a newer render request fails scene/order/atlas, camera or target checks. It accepts no new token and invokes no obsolete callback. Explicit view invalidation and failures after accepted render retain their existing lifecycle; already-transferred images remain independent. No runtime/GPU checks have been executed.
