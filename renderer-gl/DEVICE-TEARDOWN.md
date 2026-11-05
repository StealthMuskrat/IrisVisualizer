# Device shutdown, failure and retry

Renderer implementation, 2026-11-05. `GlDevice.close()` now closes its work gate independently of successful disposal, continues independent resource cleanup after errors and permits explicit close retry on the original context. All 129 production sources and four harnesses compile with Java 25/LWJGL 3.4.1; the dependency-free set also compiles. All 64 prepared groups, device failure injection and Windows GPU/native validation remain unexecuted. Unsupported previews remain disabled.

## Closing the work gate

Close first validates the original thread, GLFW context and LWJGL capabilities. It allocates a bounded identity map before changing the gate or resources. Context or bookkeeping-allocation rejection therefore leaves the prior device state untouched. The map is bounded by the existing maximum of 64 device allocations.

Once that setup succeeds, the device enters closing state before `glFinish`. New uploads, drawing, resource access/retention, allocation, staging use, collection and other operations requiring `current()` reject with STATE. A partly deleted device cannot be adopted as a new render source. Metadata-only getters retain their existing behavior. `stats()` and another `close()` remain available on the original context while closing; completed teardown keeps its existing idempotent close and closed-statistics guard.

Synchronization must succeed before any deletion or owner release. A synchronization exception or Error leaves the work gate closed, keeps all journals and charges, and stops that attempt immediately. Retry on the original context runs synchronization again. This does not assume that a failed fence or driver call completed GPU work.

Close views, queues and their workers before the device. If device teardown fails, retry the device rather than attempting new work through owners still associated with it. A successful device close releases its journals under the existing ownership contract; independently transferred CPU readback inputs remain independent and charged until their own final close.

## Dependency-aware cleanup attempts

After successful synchronization, teardown repeatedly visits allocations with no live dependents. It deletes only that allocation's objects/fences, releases its CPU owner and removes the journal after successful release. A parent becomes eligible only after its children actually release their dependencies.

| Cleanup outcome | Same close attempt | Ownership and accounting |
| --- | --- | --- |
| Eligible allocation succeeds | Remove it; continue to siblings and newly unblocked parents | Release its existing charge/dependencies |
| Eligible allocation throws RuntimeException or Error | Record the original error, quarantine that allocation and continue independent branches | Keep its unfinished journal and dependencies charged |
| Parent still has a failed child | Leave it untouched | Preserve the child's dependency pin and parent charge |
| Release already committed before shared-admission publication failed | Remove the committed journal record without replaying release | Avoid decrementing counters/dependencies twice; reconcile shared admission through later cleanup/retry |
| No eligible progress and no earlier error | Report the existing dependency-progress STATE error | Keep remaining journals and the closed gate |

The first cleanup error stays primary; later distinct errors are suppressed. A failed allocation is attempted at most once per close call, even when successful independent branches cause another dependency pass. Parents of failed children remain blocked without adding a misleading graph-progress error over the original failure. Indexed scans allocate no per-pass iterator. Each successful pass removes at least one allocation; the existing allocation bound also bounds progress passes.

Another close creates fresh bounded attempt bookkeeping and revisits unfinished leaves. Successfully deleted object names/fences and fully released journals are not replayed. Ordinary device operations remain disabled throughout retry. Staging and final shared-account disposal occur only after every journal is gone and the attempt has no recorded cleanup error. If an error was reported after all journals released, retry can finish this final disposal without re-adopting GPU work.

This is best-effort teardown. A failed native deletion or CPU owner release is not declared successful; no budget ceiling, source extent, image, block identity or native readiness condition changes. Driver/context/process-wide recovery is not guaranteed. Error aggregation cannot guarantee continued cleanup under exhaustion severe enough to prevent recording suppression metadata.

## Retirement and collection failures

Normal retirement, including immediate CPU-only journal release, now quarantines a failed release instead of leaving the journal available for adoption. Recovery goes through explicit device teardown rather than ordinary collection. Fence-based retirement rejects an unusable zero fence explicitly after checking the GL error state. RuntimeException and Error during fence creation/flush, polling, deletion or release both quarantine the affected journal and propagate the original error. Existing GPU/CPU charges and dependency pins remain until actual release; `collect()` still polls with zero wait and does not retry quarantined journals. Explicit synchronized device teardown remains their recovery route.

## Deferred verification

On genuine Windows GL at renderer completion, exercise two independent dependency branches with a shared parent and allocation order placing parents before children. Inject RuntimeException and Error in child/parent object deletion, transfer/retirement-fence deletion and CPU-owner release. Verify independent branches are attempted, failed leaves are visited once per call, their parents stay pinned, first/suppressed errors preserve identity and later close retries finish without duplicate counter/dependency decrements. Include a failure after release commitment but during shared-admission publication.

Fail synchronization and bookkeeping allocation separately. Verify no deletion before synchronization succeeds, unchanged state before gate acceptance, closed-work guards after acceptance, original-context statistics, wrong-thread/context/capabilities rejection without further mutation and a valid subsequent close retry. Cover empty devices, repeated close, final staging/account disposal failure and live independent CPU outputs. Inject CPU-only preflight-owner release failure, zero retirement fence with and without a GL error, fence/flush/poll Error, timeout, failed polling and quarantined collection. Confirm conservative charges and exact final zero ownership only after actual cleanup.

Compilation and source inspection are the current evidence. No mock device, GL context, contract harness, workflow or failure-injection execution ran for this batch.
