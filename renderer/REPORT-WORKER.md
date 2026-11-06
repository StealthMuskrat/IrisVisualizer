# Background visual limitation reports and frame handoff

Renderer implementation, 2026-11-06. Compilation/source inspection only; renderer-stage execution, genuine Windows native/GPU comparisons and final editor integration remain deferred. Unsupported previews stay disabled.

## Latest-request reporting

`WorldVisualReportWorker` runs the existing full-source [visual report census](VISUAL-REPORTS.md) on one daemon CPU executor. It owns at most one active capture, one latest pending scene and one untaken completion. Submission reads only scene usage, palette size and compact receipt metadata; assignment traversal, diagnostic hashing, example collection and sorting run off the graphics thread. There is no native sampling, GL call, generation or pack write on this worker.

`submit(freshSceneOwner, limits)` consumes a fresh independently retained `WorldScene.Result` on acceptance and rejection. Reusing a wrapper currently owned by the worker is rejected without closing that ownership. Preflight failures preserve prior pending/READY work and revision. Accepted replacements cancel obsolete active work cooperatively, close replaced pending input/untaken output and advance one token. `invalidate()` can retire work before another scene exists.

The worker closes its source wrapper successfully before publishing READY. A failed source release discards any locally created report owner and prevents successful publication. Status/completion receipts retain exact immutable assignment ID, snapshot ID, native generation/extent and asset identity, with no scene/atlas arrays. `take(revision, expectedReceipt)` withholds mismatched output without consuming it. A successful take transfers one `Completion`; its `report()` is borrowed while that completion stays open, and `close()` releases its independent report owner. Delivered reports survive worker replacement, invalidation, shutdown and original source/cache closure.

## Admission and failure recovery

Submission reserves `WorldVisualReport.workingBytes(scene, limits)` before input adoption. The existing per-report working limit applies, and the worker adds a lowerable active-plus-pending ceiling of 1 GiB. The local bound remains enforced without a shared coordinator. With `RenderMemory`, idle pending/untaken report holds may provide atomic replacement credit. Active execution stacks and externally transferred owners never provide that credit. Already tracked source/cache ownership remains separately charged; this is conservative admission rather than graph deduplication.

The worker gives the completed report a retained handle to its already-admitted hold, avoiding another source-sized account. Its original handle keeps the full execution peak charged through census, source release, completion publication and producer unwind. Only after execution returns does the drain drop its job graph, shrink the shared charge to detached report bytes and close its handle. Taking or closing READY output early cannot release active scratch admission. `reservedBytes()` includes active/pending peaks and idle untaken output, without counting same-job READY output twice; transferred owners are external.

Scheduling rejection/OOM yields a FAILED token after input/hold cleanup. Unexpected scheduling errors preserve the original error after cleanup. An escaping traversal error clears the dispatch gate after unwind and redispatches an already accepted latest request; the older failure cannot overwrite its newer status. Failed redispatch cleans that pending request and remains retryable. Shutdown seals input immediately, cancels work, attempts source/output cleanup and executor shutdown, and retries unsuccessful shutdown on later `close()` calls. It does not block waiting for active work; active reservations remain charged until unwind.

## Completed-frame adoption

Real `GlFrame.Info` values now carry optional compact `visuals()` receipts. Previous constructors remain available and leave that optional value empty; such receipts do not attest report identity. Actual frame rendering captures the exact CPU assignments used by the image. `Info.matches(reportCompletion)` checks a live report completion and exact receipt. Full-source reports can be reused across camera, clip, viewport and atlas-clock changes that retain those assignments; another native/visual assignment capture requires a new report even when the seed, snapshot data or asset hash compares equal.

```java
long reportRevision = displayed.submitVisualReport(reports, limits);
// Later, after READY: currentlyDisplayed is the host's latest displayed frame.
var displayedReceipt = currentlyDisplayed.info();
var ready = reports.take(reportRevision, displayedReceipt.visuals().orElseThrow());
if (ready.isPresent()) {
    try (var completion = ready.orElseThrow()) {
        if (displayedReceipt.matches(completion)) {
            var report = completion.report(); // Borrow only while completion stays open.
            // Read report here, or retain completion.owner() for independent ownership.
        }
    }
}
```

`submitVisualReport` requires a live frame and the same memory coordinator as the report worker before retaining a fresh CPU source owner. The accepted worker request no longer needs the GL frame to stay open. The host must compare against its currently displayed receipt; matching a saved old receipt alone does not authorize stale UI adoption. There is no automatic report polling, report cache or editor presentation in this batch. Reports describe the full captured assignments rather than only visible pixels.

## Prepared checks and remaining verification

The new world group prepares pending/READY replacement credit, invalid preflight preserving earlier work, same-wrapper misuse, exact-receipt and single-delivery guards, source release before READY, delivered ownership without replacement credit, and survival after source/worker/cache shutdown. It also prepares rejected/OOM/error scheduling and retry, failed shutdown/retry, active local/shared peak boundaries with tracked/untracked workers, invalidation during capture, latest pending redispatch after an older traversal error, redispatch failure, structured report failures and failed source cleanup without partial output.

All 132 CPU/OpenGL production sources plus four harnesses compile with Java 25/LWJGL (136 files); the dependency-free 99-file set also compiles. All 68 prepared groups remain unexecuted. Controlled cases are preparation, not PASS evidence. Genuine thread races, active take/close before unwind, GL frame/coordinator handoff, actual displayed-image adoption, Windows native/GPU behavior, performance/heap measurements and final editor report UI still require their planned stage validation.
