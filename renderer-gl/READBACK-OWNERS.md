# Owned completed-image pixel handoff

Renderer implementation, 2026-11-04. Readback can now transfer detached CPU pixels with independent shared-memory ownership. Managed output stays charged after queue and GL-device shutdown until the final CPU owner closes. All 129 CPU/OpenGL main sources compile with Java 25 and LWJGL 3.4.1 (88 CPU, 41 GL). Compilation and source inspection only: readback, worker/concurrency, failure-injection, native and real-driver comparisons remain deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Direct and queued transfer

The existing raw `GlReadback.take()` and `GlReadbackQueue.take(revision)` continue to transfer immutable `Pixels` whose subsequent accounting belongs to the caller. The new owned route uses the same complete image array and receipts, without another pixel copy:

| Operation | Result |
| --- | --- |
| `GlReadback.takeInput()` | `RenderInput<GlReadback.Pixels>` |
| `GlReadbackQueue.takeInput(revision)` | `Optional<OwnedCompletion>` |
| `OwnedCompletion.pixels()` | Borrowed immutable packet from its live input |
| `OwnedCompletion.input().retain()` | Independent CPU owner of the same packet/admission |
| Owner/completion `close()` | Clears that wrapper's value and releases its hold |

Take still runs on the original context thread after complete READY publication. Either raw or owned take consumes the job once; the other route cannot transfer it again. Queue transfer retains the same exact-revision guards, including newer-token invalidation and rejection of stale/duplicate takes. The completion contains the request revision and an independently closable input wrapper.

`Pixels` retains exactly one original world/map/scalar/source-image receipt and its complete top-left RGBA image. It contains no scene/sample array, encoded image, framebuffer, callback, GL source owner or device. UUID, camera/clip/clock, source dimensions, display metadata and native provenance remain the original completed-image receipts. The owned route changes neither pixel bytes nor receipt identity.

## Shared admission across device release

For a device constructed with `RenderMemory`, transfer first admits an independent hold for the complete output image byte count. Only after the wrapper and, for queued transfer, the completion/Optional/delivered status are allocated does it consume the device-owned output journal. The same image array then belongs to the CPU wrapper, with no source-size scratch or second RGBA array.

The device and destination hold overlap conservatively during admission. A shared byte/account limit can therefore reject transfer even when the eventual single output charge could fit after release. This preserves the established admission-before-release rule. Existing readback/device/shared ceilings remain unchanged, and retired pixel buffers/source dependencies continue to carry their own device charges.

After transfer, device CPU statistics no longer include the taken output. The shared coordinator still counts its independent CPU hold, including after the GL device closes. `RenderInput.retain()` creates another wrapper sharing that one account rather than charging the image twice. The last wrapper close releases the output's byte charge and account slot; coordinator closure still permits these decreases/releases.

Without a device coordinator, the owned route creates an untracked input wrapper, matching other compatibility APIs. Its close still controls that wrapper's strong reference, while total application admission remains the host's responsibility. Java objects/receipts and arbitrary escaped borrowed references retain their existing external accounting boundary.

## Rejected handoff and cleanup

Shared admission failure reports `GlFailure.Code.LIMIT`; a closed coordinator reports STATE. Admission/setup failure before journal consumption leaves direct READY output and queue READY status, pixels, revision and progress intact. Any temporary hold is released if completion/status construction fails. The host can release other CPU owners and try owned take again, choose its explicitly externally admitted raw route or close/invalidate the job.

If actual journal consumption/cleanup fails after transfer begins, a new CPU wrapper is not returned and its hold close is attempted. A distinct input-release error is suppressed onto the original transfer error. Queued transfer still attempts active-job cleanup and FAILED publication even if input release also fails, preserving unfinished owners or deferred/quarantined device charges. No partial image is published and no charge is dropped merely because a wrapper allocation succeeded.

Direct raw `GlReadback.take()` now enters FAILED and attempts disposal if output-journal retirement throws. Retirement may already have released or quarantined the journal, so that failure cannot leave the old pixel packet exposed as READY. Any distinct disposal error is suppressed onto the original retirement error. Direct owned take inherits this terminal boundary and preserves that original error through closing its unreturned CPU input. The initial READY/context check and owned admission/setup still precede journal consumption; their rejection preserves the existing output.

Raw queued take now allocates its completion, Optional and DELIVERED status before entering transfer cleanup, matching the existing owned setup boundary. Metadata allocation failure preserves exact READY status, revision, progress, active ownership and pixel journal. It acquires no new owner, so nothing needs to be disposed at that point. Once consumption begins, both routes retain ordinary FAILED cleanup and same-token queue recovery.

| Failure boundary | Output state and cleanup |
| --- | --- |
| Raw completion/Optional/status setup | Queue remains READY with the same device-owned pixels |
| Owned admission or completion/Optional/status setup | READY is preserved; any temporary CPU input is closed, with release failure suppressed onto setup failure |
| Direct output-journal retirement | Job becomes FAILED and drops its completed pixel reference; disposal preserves device-owned unfinished/quarantined resources |
| Owned transfer plus CPU-input close failure | Original transfer error stays primary; queued job cleanup and FAILED publication are still attempted |
| Wrong context, stale/duplicate token or no READY output | Existing preflight/token behavior applies before transfer |

Taken CPU inputs are independent of future queue requests, invalidation and shutdown. They may be read, retained and closed on CPU threads after device teardown. Keep an owner live through its last use, and retain before sharing its borrowed value with an independently scoped consumer. Closing a completion does not revoke another retained input; retaining a raw `Pixels` reference alone does not extend its admission.

## Deferred verification

At renderer completion, compare raw and owned pixel bytes and receipt identity for world/map/scalar/PNG/JPEG frames, including odd dimensions, alpha, row reversal and maximum image bounds. Exercise mixed take routes, current/stale/future tokens, raw/owned duplicate take, READY-before-invalidate and closed/noncurrent context rejection.

Check exact image charge through destination admission, device-journal release, queue/source/device close, multiple retained CPU owners, last close, closed coordinator and untracked compatibility. Admission must include conservative overlap and account slots; rejection must preserve READY data/status/progress and leave no empty account. Inject hold/input/completion/Optional/status allocation failures before transfer, journal cleanup failure after transfer starts and repeated close. Include retirement failures before and after release/quarantine commitment; verify no subsequent direct raw/owned take can observe that failed packet as READY. Combine setup/transfer errors with CPU-input and job-release errors, checking exact primary/suppressed identity, every cleanup attempt and queue FAILED/retry behavior. Verify independent CPU-thread retain/read/close, old-output ownership through new readbacks, original receipt/pixel identity and final shared/device charges. Full and dependency-free compilation passed; all 64 prepared groups and these real-driver/native failure checks remain unexecuted. Final editor integration remains pending.

## Preserved output and failed-job recovery

[Readback queue preflight](READBACK-RECOVERY.md) now preserves earlier READY output when an invalid replacement source is rejected before admission. Actual FAILED jobs can explicitly reopen the current token after cleanup. Owned-take admission rejection still leaves READY intact, so retry the take operation directly; queue retry intentionally rejects READY/DELIVERED phases. Taken CPU owners keep their independent charge and pixels. Runtime/GPU validation remains deferred.
