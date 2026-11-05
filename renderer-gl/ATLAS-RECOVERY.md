# Atlas replacement preflight and recovery

Renderer implementation, 2026-11-05. Atlas queue submission now validates the displayed scene and exact CPU raster baseline before replacing earlier animation work. The exact current failed scene/frame request can also reopen after cleanup. This batch was source-reviewed through GitHub only: the execution environment is unavailable, so these changes have not been compiled or executed. The preceding commit compiled 129 production sources plus four harnesses; that evidence does not validate this new source revision. The 55 prepared contract groups remain unexecuted, and unsupported previews remain disabled.

## Preserve work on invalid same-scene replacements

Previously, a newer frame token reached queue observation before the supplied displayed frame and CPU plan were checked. Observation advanced the token and released earlier work, including an untaken READY atlas. Only afterward could upload begin reject a closed/foreign scene, a failed plan or a plan for another exact raster.

For submissions targeting this queue's current GPU scene, the queue now checks positive tokens and stale/duplicate phases first, then checks the live displayed scene and complete plan's exact raster/layout identity. A failed preflight leaves the existing frame revision, status object, progress, upload owner and untaken result unchanged. It creates no replacement upload journal, retained source frame or GPU page. Stale/duplicate submissions return false without inspecting a potentially closed displayed frame.

`GlAtlasUpload.validate` centralizes the constant-time metadata/liveness checks used by queue preflight and direct upload begin. Begin repeats validation after accepted queue replacement, before admission. Actual admission, setup, transfer and cleanup failures still follow the existing FAILED/retirement path. Started GPU work is not rolled back by this preflight change.

The separate GPU scene revision keeps its existing authority. A newer scene revision obsoletes this queue and retires its work immediately; an older scene request is ignored. Explicit `invalidate(sceneRevision, frameRevision)` still abandons earlier work before a replacement plan exists. Use that operation when upstream inputs are obsolete even if a replacement is invalid or unavailable. A rejected same-scene submission does not accept its new frame token.

## Explicit current-failure retry

`GlAtlasQueue.retry(expectedScene, expectedFrame)` checks the original context and both positive tokens. It returns true only for the exact current scene/frame pair while FAILED and not obsolete. It allocates a WAITING status before cleanup and publishes it at the same tokens only after releasing remaining queue-owned work.

| State or token | Behavior |
| --- | --- |
| Current pair, FAILED, cleanup succeeds | Return true, publish WAITING, preserve both tokens |
| Stale/future pair, obsolete scene or any other phase | Return false without mutation |
| Nonpositive token, closed queue or wrong context | Explicit error |
| Cleanup failure | Retain FAILED diagnostics and reachable unfinished owner; rethrow |

After success, submit a complete plan against the still-displayed frame using the ordinary raw or consuming owned-input overload. Retry retains no new plan or frame, performs no native sampling or CPU preparation, and starts no automatic loop. The connected world animation controller keeps its existing externally clocked recovery; this lower-level queue API does not change its clock policy.

Raw callers keep their CPU admission and displayed owner alive through submission. Owned inputs remain consumed on every outcome and require the same coordinator when tracked. A retained independent input survives a failed attempt. Retry cannot revive an obsolete GPU scene or adopt a plan from another baseline. READY/DELIVERED work cannot restart through this API.

Cleanup uses the existing owner-release path. Resources still owned after a cleanup exception remain reachable for another retry/close; device-retired or quarantined allocations retain their charges and dependency lifetimes. Caller-owned displayed frames and previously transferred completed images remain independent. No pixel, geometry, animation schedule, shader, layout or memory ceiling is changed.

## Deferred verification

Compile the updated production sources before claiming validation of this revision. At renderer-stage execution, submit newer same-scene frame tokens with closed/foreign displayed frames, failed plans, matching-looking different raster captures and layout mismatches while the queue is WAITING, UPDATING or READY. Verify identical prior status/token/progress/owner/result on preflight rejection, no new journals/page owners and ordinary stale/duplicate short-circuit behavior.

Exercise explicit invalidation separately, including newer GPU scenes becoming STALE regardless of candidate plan, and original-context/device enforcement. Cover current/stale/future/nonpositive retry pairs, obsolete queues, every nonfailed phase, repeated calls, status allocation failure before cleanup and failed cleanup retaining ownership. Verify successful same-pair raw/owned resubmission, foreign coordinators, consumed-input failure, retained displayed/old completed frames and final retirement/quarantine charges. Compare original and recovered atlas pixels/receipts on real Windows GL. Native/GPU comparison and final editor integration remain pending.
