# GPU transfer replacement admission

Renderer implementation, 2026-11-06. Compilation and source inspection only. No GL context, renderer contract or native comparison has run. Renderer execution and final editor integration remain deferred; unsupported previews remain disabled.

## Preserve accepted work through initial admission

`GlUploadQueue`, `GlMapQueue`, `GlImageQueue`, `GlReadbackQueue` and `GlAtlasQueue` now create and admit a fresh transfer before retiring accepted queue work or spending its replacement revision. Previously, a valid new source could invalidate an accepted upload or READY completion before its first device/shared-memory reservation failed.

A rejected fresh transfer leaves the exact prior queue token, status, progress and queue-owned source/output available. The caller receives the structured exception and can continue advancing or take the earlier accepted result with its earlier token. A first submission that cannot be admitted leaves IDLE or the explicitly invalidated WAITING state intact. Retrying the same unspent replacement token uses ordinary `submit`; the queue's explicit `retry` operation continues to reopen an already accepted FAILED transfer.

On successful preparation, the queues construct their next status before fallible retirement of earlier work. They retire the old owner, install the admitted replacement, and then publish its token/status without allocating more metadata. A failure while constructing that status releases the fresh transfer while preserving old work. An old-owner retirement failure marks the previous accepted revision FAILED and closes the uninstalled replacement. Primary errors retain distinct cleanup errors as suppressed exceptions. Failed device cleanup remains covered by its journal/quarantine and normal device teardown.

## Ownership and conservative overlap

Fresh world CPU scene leases remain consumed on every outcome. Already-device-owned lease wrappers remain protected. Raster/image sources remain borrowed. Readback frames and displayed atlas baselines remain independently caller-owned; successful new readback/atlas preparation retains their existing exact source receipts. The owned atlas-plan overload consumes its fresh wrapper on rejection as before.

Fresh admission must fit alongside all existing live/retired payload. This adds no replacement credit or increased memory/object ceiling. The device can collect unrelated signalled retirement fences during a rejected reservation, so its aggregate counters may decrease; a rejected candidate adds no retained payload, GPU names or quarantine. A candidate that cannot overlap old work rejects explicitly. The host can explicitly invalidate an obsolete request first when it intends to abandon it and release its resources.

Explicit `invalidate`, newer-token observations in `advance`/`take`, and changed atlas scene epochs keep their immediate invalidation behavior. Stale or duplicate successful submissions remain rejected. The atlas queue still verifies the exact displayed baseline before preparation and adoption. Revision values continue to come from the caller; admission does not rewind an edit token or authorize adopting output for an obsolete edit.

## Connected world view

`GlWorldView.submit` now computes its next view status/token, submits through the upload queue, and only after acceptance resets ordering, frames, animation and the old scene. An initial upload-admission rejection preserves the current world/view tokens, camera/clip, upload work, READY frame and source readiness. It consumes only the fresh submitted CPU owner. A later accepted revision follows the existing invalidation and cleanup path. Errors after that acceptance mark the new view FAILED and retain the ordinary explicit upload-retry route.

Calling `GlWorldView.invalidate` when generation inputs become obsolete still abandons the old view immediately, even before replacement CPU data exists. Admission rejection after such an explicit invalidation leaves the new WAITING_CPU state; it does not revive obsolete world data. This change supports callers submitting a complete replacement directly as well as the existing generation-invalidation workflow.

## Incremental work and remaining scope

Initial admission now covers the [complete known raster/scalar/readback storage footprint](FULL-STORAGE-ADMISSION.md). World validation and its subsequent storage expansion retain their incremental contract. Native storage creation, data transfer and rendering retain their existing incremental phases and budgets. A later validation, storage, GL or driver failure still fails the accepted replacement revision. This does not promise rollback after GPU work starts, change completed-frame rendering/admission, or bypass native/asset readiness. Caller-transferred immutable images retain their existing independent lifetime.

Two new GPU groups prepare ACTIVE/READY admission rejection across all five transfer queues and the connected world view. They leave one byte less shared allowance than the fresh journal requires, check preserved tokens/progress/output and host state, fresh scene/plan consumption, no new device payload/names/quarantine, original receipt/pixel delivery, and later successful pending replacement. Retirement/fence collection is permitted to decrease counters. Startup/status/old-owner cleanup fault injection, active driver failures, thread races, performance and genuine Windows/native pixels remain in the deferred validation matrix.

All 137 production sources, five harnesses and one fixture compile with Java 25/LWJGL (143 files). The unchanged dependency-free set contains 105 files. All 96 prepared groups remain unexecuted: 76 CPU/context-free and 20 GPU. No renderer-stage completion or GPU/native correctness is claimed.

Complete transfer admission now includes raster textures, scalar geometry/color buffers and VAO, and readback pixel-pack buffers. A known GPU/object deficit rejects before map/image/readback queue replacement; unused storage refunds without fencing. All 148 prepared groups remain unexecuted.
