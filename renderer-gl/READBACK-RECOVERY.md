# Readback preflight and explicit recovery

Renderer implementation, 2026-11-05. Readback queue replacement now checks source/device/lower-limit compatibility before invalidating earlier work. The exact current failed request can also be reopened explicitly after cleanup. All 129 production sources and three prepared harness sources compile with Java 25 and LWJGL 3.4.1 (132 total). No contract, readback, GPU, native or workflow tests ran; stage execution remains deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Reject invalid replacements before mutation

Previously, `GlReadbackQueue.submit(newRevision, frame)` invalidated the preceding job before `GlReadback.begin` checked the replacement's complete target and size allowances. Device identity was checked only after begin had retained the source and admitted work/output journals. A closed, oversized or foreign-device replacement could therefore discard a previous READY result before being rejected; same-context foreign devices could also receive temporary allocations before the mismatch was reported.

All four completed-frame overloads now pass a lazy target reader through one queue submission path. The sequence is:

1. Check original queue context, positive token and current submit phase.
2. Reject stale/duplicate tokens without reading or retaining the supplied frame.
3. Resolve the live completed target, require the queue's exact device and check configured pixel/working/row-staging limits.
4. Only after successful preflight, invalidate older work and begin the new readback normally.

Step 3 failure leaves the previous revision, phase, status object, progress, active job and untaken pixels unchanged. No replacement frame owner, readback journal, PBO or pixel array is allocated. Wrong-context source access and closed/incomplete targets likewise fail before replacement mutation. Null inputs and nonpositive tokens retain their explicit errors.

Direct `GlReadback.begin` shares the same target validation, so direct and queued dimensional/working checks cannot drift. Begin still validates again after queue invalidation and retains the existing defensive post-begin device check. Device/shared allocation admission, setup or cleanup failures after acceptance follow the normal FAILED path; this is preflight preservation, not an atomic rollback of a started GPU request.

Explicit `invalidate(newRevision)` still discards old work immediately. Use it when upstream image inputs become obsolete before a valid replacement is available. Rejected submission does not accept its new token, and the host still checks its desired revision and the completed image UUID before displaying/exporting output.

## Retry only a current failure

`GlReadbackQueue.retry(expectedRevision)` runs on the original context thread. It performs no pixel transfer and retains no new source.

| Condition | Result |
| --- | --- |
| Exact positive current token, FAILED phase, cleanup succeeds | Return true and publish WAITING at the same revision |
| Stale/future token or any nonfailed phase | Return false with no change |
| Nonpositive token, closed queue or wrong context | Existing explicit error |
| Cleanup failure | Keep FAILED diagnostics and unfinished ownership; rethrow |

The WAITING status is allocated before cleanup. Remaining active readback owners are disposed through the existing release path; ownership is dropped only after cleanup succeeds or the device has accepted retirement/quarantine. Only successful cleanup publishes WAITING. A later retry or close may revisit resources still owned by the queue. Device-retired/quarantined work remains charged under existing fence and dependency rules.

After true, call the usual `submit(expectedRevision, retainedFrame)` and pump `advance` normally. Keep an independent complete frame owner if the original request may need to be retried; the failed readback's internal source owner is cleanup data and is not a recovery source API. The queue's caller-supplied revision remains the adoption token. The host supplies the intended image and checks its actual output receipt; retry does not manufacture or reconstruct a discarded frame.

READY, DELIVERED and READING work cannot be restarted through retry. In particular, an owned-take admission/setup rejection already preserves READY pixels, so repeat `takeInput` after releasing other memory rather than restarting readback. Previously transferred raw/owned pixel packets remain independent of queue replacement, retry and shutdown.

No sample, geometry, color, pixel orientation, framebuffer state, shader, image resolution or native identity changes. Existing whole-row transfer/action/work/cooperative-time bounds, zero-timeout fence polling and memory ceilings remain unchanged. No automatic retry loop or background GL thread is added.

## Deferred verification

At renderer completion, preserve READY/READING/WAITING work while submitting closed, incomplete, foreign-device and over-limit world/map/scalar/original-image frames. Check the same status object, token, progress, job ownership and pixel bytes after preflight rejection, with no replacement journals/holds/frame owners. Include two devices on one context, foreign contexts/capabilities, stale tokens with closed frames and exact lowered pixel/working/row-staging boundaries.

Inject failures after accepted invalidation, during journal/setup/storage/read/fence/copy/finishing and while retiring source/work/output. Verify FAILED current-token retry, stale/future/nonpositive/closed guards, preallocated status failure before cleanup, repeated cleanup failure, same-revision resubmission, no READY/delivered retry and conservative retired/quarantined accounting. Retained original frames and transferred pixel inputs must keep their independent lifetimes. Compare all RGBA bytes, row orientation and original receipts on real Windows GL. Native/GPU validation and final editor integration remain pending; none of these runtime checks has been executed.
