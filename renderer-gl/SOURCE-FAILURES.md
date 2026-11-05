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

## Deferred verification

At renderer completion, inject invalidation/upload/old-owner cleanup failures before and after source-token changes in all four views. Preserve the old owner deliberately, then change camera, viewport, clip, fit/pan/zoom where permitted, redraw, advance and take. Verify no old-source draw, atlas work or frame transfer occurs under replacement tokens, and source diagnostics remain available through navigation. Include no-op/invalid display changes, repeated failures and retrying close with live caller frames.

Check worker admission rejection before mutation, successful replacement after failure, stale/current take tokens, complete upload before viewport configuration, and allocation failure while preparing world invalidation/delivered status. Verify valid display-failure retry, transparent ordering and externally clocked animation with a complete current scene. Exercise scalar convenience versus explicit retained-source recoloring and source/geometry mismatch guards. Confirm old-frame pixels/receipts, exact native values and all retained/retired/shared charges until real cleanup completes. Runtime, real-driver/native comparison and final editor integration remain pending; no renderer-stage tests ran for this batch.
