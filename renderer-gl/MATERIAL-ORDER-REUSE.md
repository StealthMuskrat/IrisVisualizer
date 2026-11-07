# Transparent face order across material refresh

Renderer implementation. Full Java 25/LWJGL compilation covers 143 renderer/validation sources; the dependency-free set contains 105 files. Three added GPU groups bring the prepared total to 108: 77 CPU/context-free and 31 GPU. All remain unexecuted. Native/GPU validation, application memory confirmation and final editor integration remain pending; unsupported previews stay disabled.

## Exact geometry and pose

World material refresh copies the existing geometry, indices, markers and displayed atlas into independent GPU storage, changing tint/final lighting only. It preserves the exact immutable CPU scene and its translucent centroids. Previously the connected view discarded its completed transparent-face order and ran the full background depth/sort pass again after each refresh.

`GlDrawPlan.rebind(destination)` now admits an independent plan for the same device and exact CPU scene. It shares the immutable `WorldDrawOrder` object and its original camera pose, without recomputing depths, sorting keys or copying arrays. The ordinary complete-order, exact-scene and translucent-count checks run before admission. Equal-valued scenes from different CPU epochs are not interchangeable. Rendering still requires the original exact camera pose.

The live source plan's device journal pins its source GPU allocation and CPU scene even after the public source scene wrapper closes. Rebinding can therefore finish after the source wrapper is closed during material upload. A closed/retired plan or closed destination still rejects normally. Foreign devices reject before destination admission.

## Independent admission and retirement

Each new plan independently reserves the full order payload under existing CPU/device/shared-memory and allocation ceilings. Sharing a Java array does not supply release credit. Old and new order charges overlap until the prior plan closes. No GPU names, geometry buffers, larger limits or additional worker threads are introduced.

The new journal depends only on the destination scene. It retains no old plan journal, source GPU allocation or ancestor scene chain. Once copying and old owners finish, the source can retire under ordinary fence/dependency accounting while the new plan stays usable. Repeated material refresh follows the same contract.

If initial destination admission fails, rebinding preserves the source plan and both scenes. There is no partial plan. Setup rollback retains the existing primary-error/suppressed-cleanup behavior from `GlDrawPlan.create`.

## Connected view handoff

Both `GlWorldView.materials` and `materialsLit` first admit the fresh upload. Initial upload rejection leaves the existing view, frame and order untouched. Once admitted, the view invalidates the old worker token and moves its completed plan into one pending-material owner slot. Incomplete or missing worker results are never treated as complete reusable orders.

After source/initial-atlas readiness, the view verifies the retained pose and admits the destination plan before closing the old plan. Only then does it render. The old journal continues charging/pinning its source during upload and admission, including through an explicit failure retry. A successful destination plan is installed before old cleanup, so fallible retirement cannot strand it in a local variable. A consumed retirement owner is cleared; an owner still requiring cleanup stays reachable.

| Change | Order behavior |
| --- | --- |
| Tint/final RGB material refresh | Rebind completed order for the exact CPU scene |
| Viewport size, field of view, near/far planes | Keep order when the exact camera pose stays equal |
| Geometric clip or atlas clock | Keep order; geometry/centroids stay unchanged |
| Camera eye, target or anchor | Discard retained order and prepare the new exact pose |
| Full world submission, invalidation or source-upload retry | Release retained material order and use ordinary source sorting |
| No prior complete order | Use the ordinary asynchronous ordering worker |

Pose validation also runs before reuse after cleanup retries, preventing a retained obsolete-pose cleanup owner from becoming usable again. Reset/release attempts visit both current and pending-material plans even after an earlier close error, preserving the primary failure and suppressing later errors. Another material refresh cannot overwrite an outstanding prior cleanup owner.

## Recovery and bounded lifetime

Destination order-admission failure makes the view FAILED while retaining the complete replacement scene and old order owner. No new frame is published. After pressure is removed, `redraw()` retries that admission without a source upload or another sort. The source-upload retry path remains reserved for incomplete sources. Existing animation recovery retains the pending order when its pose is still eligible.

Camera/world invalidation, retry and shutdown release the pending-material slot. Active cancelled worker jobs remain charged until unwind. Caller-transferred images retain their original source, pixels and picking ownership across later replacement, failure and view shutdown. Primitive accounting still excludes JVM/driver overhead and does not establish total application memory readiness.

## Prepared deferred coverage

Three GPU groups prepare direct rebinding, connected reuse and recovery. Scope includes exact immutable object/key reuse, independent order-byte admission, host state, closed public source wrappers, original GPU ancestor release, repeated rebinding, fresh full-upload pixel comparison, closed/foreign/wrong-epoch rejection, deficient shared memory, empty orders, raw/owned lighting refresh, projection/resize/clip preservation, pose and full-world invalidation, pending-refresh shutdown, saved-frame lifetime, explicit redraw after admission failure and fallback when a camera/order was not available before refresh.

These are compiled assertions only. No GL context, renderer contract, native producer, workflow or runtime pixel comparison was executed for this implementation batch.
