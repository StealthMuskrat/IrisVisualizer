# Admitted world navigation

`GlWorldView` now admits replacement frame storage, an eventual order journal when required, and any newly needed asynchronous sort request before accepting camera/viewport, clip, fit or redraw changes on a complete source. Known capacity rejection preserves the earlier READY or undrawn view, its revisions, clip and ordering work. Source generation, meshing and uploads retain their separate policies.

## Frame storage before late inputs

`GlFrame.reserve` creates an unused preparation without requiring a completed transparency order or a final animated atlas. It validates renderer/source identity, camera precision and viewport bounds, retains the original CPU scene lease, pins the GPU scene allocation and admits:

| Component | Complete reservation |
| --- | --- |
| Frame journal | One journal; a CPU atlas upper bound equal to all layout-page RGBA bytes |
| Draw scratch | One journal; exact existing hierarchy-mode world culling allowance |
| Target | One journal; `width * height * 8` GPU bytes |
| Object slots | One color texture, one framebuffer and one depth renderbuffer |

The temporary atlas upper bound covers any later combination of replacement pages in the exact scene layout. This is conservative: even navigation on a currently unchanged atlas must fit that upper reservation. No target handles, storage, culling arrays or sort arrays are created by frame admission. Existing direct `GlFrame.prepare`/render paths retain their tighter exact-atlas admission.

At draw time the preparation validates the actual complete atlas and camera-bound order. `GlDevice.shrinkCpu` refunds the unused atlas upper allowance before native target storage, leaving only the exact changed-page CPU charge. Shrinking requires original-device membership and cannot grow a reservation. Actual raster/clock, source/lighting/visual receipts and the private target transfer to the completed frame after successful drawing and restoration.

`GlFrameQueue.renderPrepared` accepts an existing unused reservation only when renderer, scene, view revision, camera, viewport, clip and strategy match the request and the actual atlas fits. A rejected binding preserves both queue state and unused preparation. Accepted drawing consumes the preparation on every outcome, with the established obsolete-callback and reentrancy rules. No second frame reservation occurs.

If animation adopts a completed atlas while navigation waits, an unused preparation can advance its revision without changing its source, camera, viewport or clip. Its existing upper bound covers the newly bound pages. The final frame records the actual adopted atlas and view revision. Future animation preparation/upload still has its own admission and failure policy; a frame reservation does not guarantee those independent operations succeed.

## Transparency destination and worker admission

`GlDrawPlan.Prepared` admits one destination journal and eight retained key bytes per original translucent face before the new order is available. It pins the exact destination GPU scene. Completing a successful exact CPU order or rebinding a compatible material order consumes this reservation without another capacity request. Public create/rebind paths retain their original signatures and one-journal footprint.

New direction-dependent sorting is submitted only after frame and order-destination admission. The existing `WorldOrderWorker` admits its complete source/sort peak before cancelling previous work. Shared-memory or worker-budget rejection before acceptance closes fresh scene leases and refunds all new device reservations, preserving accepted view/order state. Once admitted, sorting remains off-thread and uses the existing original face keys and deterministic depth/tie policy.

Compatible translations/projection changes keep complete or pending canonical orders and their worker tokens. A pending request can reuse its worker result while retaining its separately admitted destination journal. Accepted changed directions retire old plans after the new worker request fits. Material refresh can bind its retained compatible order into an admitted destination without sorting or another order journal reservation.

## Controller lifecycle

Changed camera/viewport, changed clip, fitting and explicit redraw first obtain the applicable admissions. Equal camera/viewport and clip requests remain no-ops. Successful acceptance advances the view revision, invalidates earlier queue-owned output and retains the unused frame/order preparations until `advance`. A newer request must fit alongside old reservations before superseding them. There is no release credit; live/retired ownership, worker overlap and existing shared/device limits remain charged. Automatic retirement may reclaim unrelated eligible resources.

Source invalidation, accepted source/material replacement, terminal animation/order failure and closure abandon unused preparations. Cleanup visits pending order/frame owners as well as original scene/animation resources and preserves the primary error. Started resources retain normal fenced retirement and quarantine rules. Camera/clip metadata while no complete source/camera is available retains its earlier configuration behavior.

Frame admission does not guarantee driver allocation, state restoration, metadata allocation or a successful native visual result. Caller-owned older images retain their immutable pixels and original CPU/native identity through replacement and failure. No client asset, full-height capture, native state, transparency coverage, picking or unsupported-preview gate changes.

## Prepared validation

Three GPU groups prepare unused/unbound frame counters and refunds, order destination admission, zero-additional-allowance binding, single consumption, revision checks, rejected queue bindings, material-order rebinding and CPU upper-bound refund guards.

Connected cases cover opaque, translucent, empty, diagnostic and animated scenes; atlas/scratch/target admission deficits; worker admission failure; exact frame peaks; compatible order reuse; pending-sort completion with no additional allowance; differential pixels/native receipts; animated atlas adoption retokening; source invalidation; pending controller closure; and material refresh during admitted navigation. Independent GPU-byte, journal and object-slot limits prepare READY/undrawn rejection and reuse of the unspent view revision after output releases.

All 144 full renderer/validation sources and the 106-file dependency-free set compile with Java 25 (LWJGL 3.4.1 for the full set). There are 175 prepared, unexecuted groups: 109 CPU/context-free and 66 GPU. No renderer test main, context, shader, native operation, Windows validation task or workflow ran. Genuine native/API compatibility, GPU correctness/fault injection, total-process memory/performance measurement, renderer completion and final editor integration remain pending. Unsupported previews stay disabled.
