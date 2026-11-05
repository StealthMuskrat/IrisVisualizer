# Completed-frame replacement preflight

Renderer implementation, 2026-11-05. World completed-frame rendering now checks borrowed source/order/atlas metadata, camera pose and target dimensions before a queue accepts replacement of earlier untaken output. All 129 CPU/OpenGL production sources plus four prepared harnesses compile with Java 25 and LWJGL 3.4.1 (133 total). No renderer contract, native adapter, GL context, GPU draw/readback or workflow test ran. The 55 prepared groups remain unexecuted and unsupported previews stay disabled.

## Preserve an earlier completed image on invalid input

Previously, a newer `GlFrameQueue.render` token invalidated the queue's prior completed frame before `GlFrame.render` checked scene/device identity, the camera-bound translucent order, the displayed atlas and camera pose. Target limits were checked even later during allocation. An invalid request could therefore discard untaken READY output before rejection.

Queue submission now checks positive tokens and eligible phases first, then uses shared `GlFrame.validate` before accepting a newer revision. Preflight failure leaves the prior revision, status object, source/frame receipt, queue-owned completed frame and its dependencies unchanged. The invalid request creates no new target, journal or independent CPU/source owner.

| Preflight check | Rejection before replacement |
| --- | --- |
| Renderer and scene liveness / exact device | Closed/retired or foreign-device input |
| Required translucent order | Missing complete order for a scene with surviving translucent faces |
| Supplied order device, exact scene and camera pose | Foreign or stale order |
| Supplied atlas exact GPU scene and liveness | Foreign/closed displayed atlas |
| Camera basis/projection | Invalid aspect or unusable viewing direction |
| Target dimensions/pixels and texture/renderbuffer bounds | Configured or hardware limit exceeded |

Stale/duplicate submissions still return false before borrowing source resources. Request construction retains its existing nonnull/positive-shape checks. Preflight does not consume caller-owned scenes, atlases or orders, and no full scene/face/vertex scan is added. Camera calculation uses bounded matrix/basis scratch, not source-size data.

## Shared direct-render and target checks

Direct `GlFrame.render` reuses the same metadata preflight before retaining the source scene and reserving its image journal. Direct and queued rendering therefore agree on scene/order/atlas identity and dimensions. The existing source/context checks and initial obsolete-supplier gate remain before the direct render's preflight, preserving its cooperative cancellation contract.

`GlTarget.validate` centralizes configured dimension/pixel and hardware texture/renderbuffer checks. Both preflight and actual target creation use it; actual creation repeats validation before admission. Target format, clear values, storage charge, framebuffer attachments and host-state guards are unchanged.

Actual device/shared admission, section-relative offset validation, renderer work, host-state capture/restoration and draw failure remain in the accepted render path. Passing metadata preflight does not certify every geometry/driver condition or promise current combined memory capacity. An error after accepted invalidation follows the existing FAILED/cleanup path rather than rolling back a started request. No source pixels are overwritten, and previously transferred independent frames retain their original data.

## Invalidation and callbacks

Explicit `invalidate(newRevision)` still abandons earlier queue-owned images immediately when upstream presentation is obsolete. Connected view operations retain their current explicit invalidation policy; a rejected later render cannot restore something already invalidated. The host must still adopt only its desired source/view token.

Preflight invokes no caller callback. The obsolete supplier is still consulted only after render acceptance, with the queue's rendering guard active. Its side-effect-free requirement and the rejection of reentrant render/invalidate/take/close remain unchanged. An accepted request that is already obsolete follows the existing CANCELLED path; this change does not reinterpret cancellation as a metadata rejection.

## Deferred verification

At renderer completion, keep a queue in READY or WAITING while submitting newer requests with closed/foreign scenes, missing translucent order, foreign/closed or camera-stale order, foreign/closed atlas, coincident camera eye/target and oversized targets. Verify the same prior token/status/receipt/frame ownership on preflight rejection, no new journals/targets/CPU leases and continued take of prior READY output when still desired. Check stale/duplicate tokens with unusable borrowed inputs and successful corrected submission at unchanged WAITING tokens.

Compare exact target dimension/pixel/hardware boundaries in direct target creation, direct frame rendering and queue rendering. Exercise explicit view/queue invalidation separately. Inject admission, source-retain, target creation, section-offset, hostile host-state, draw/restoration and status-publication failures after acceptance; preserve incomplete-output suppression and retirement/quarantine charges.

Check obsolete-supplier call timing and reentrant operation rejection, cancellation before/after draw, retained old frames through source/queue/renderer shutdown and exact camera/atlas/clip/lighting receipts. Compare world pixels and readback output on real Windows GL. Native parity, genuine compilation/execution of sixteen native adapters and final editor integration remain pending; these runtime checks have not been executed.
