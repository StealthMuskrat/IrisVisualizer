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

If actual journal consumption/cleanup fails after transfer begins, a new CPU wrapper is not returned and its hold closes. Queued transfer follows the existing failure/cleanup path and exposes FAILED diagnostics, preserving unfinished owners or deferred/quarantined device charges. No partial image is published and no charge is dropped merely because a wrapper allocation succeeded.

Taken CPU inputs are independent of future queue requests, invalidation and shutdown. They may be read, retained and closed on CPU threads after device teardown. Keep an owner live through its last use, and retain before sharing its borrowed value with an independently scoped consumer. Closing a completion does not revoke another retained input; retaining a raw `Pixels` reference alone does not extend its admission.

## Deferred verification

At renderer completion, compare raw and owned pixel bytes and receipt identity for world/map/scalar/PNG/JPEG frames, including odd dimensions, alpha, row reversal and maximum image bounds. Exercise mixed take routes, current/stale/future tokens, raw/owned duplicate take, READY-before-invalidate and closed/noncurrent context rejection.

Check exact image charge through destination admission, device-journal release, queue/source/device close, multiple retained CPU owners, last close, closed coordinator and untracked compatibility. Admission must include conservative overlap and account slots; rejection must preserve READY data/status/progress and leave no empty account. Inject hold/input/completion/Optional/status allocation failures before transfer, journal cleanup failure after transfer starts and repeated close. Verify independent CPU-thread retain/read/close, old-output ownership through new readbacks, original receipt/pixel identity and final shared/device charges. Real-driver/native comparison and final editor integration remain pending; no renderer-stage tests ran for this batch.
