# Map and image replacement preflight

Renderer implementation, 2026-11-05. The lower-level map and original-image upload queues now validate complete CPU input and hardware/row-staging compatibility before replacing earlier work. The restored workspace also compiled the preceding atlas preflight/retry changes. All 129 production sources plus four harnesses compile with Java 25 and LWJGL 3.4.1 (133 total). The 55 prepared groups remain unexecuted; renderer-stage runtime/native/GPU validation is still deferred and unsupported previews remain disabled.

## Validate before discarding prior work

Previously, `GlMapQueue.submit(newRevision, raster)` and `GlImageQueue.submit(newRevision, sourceImage)` called `invalidate` before upload begin checked the candidate. A failed CPU raster/decoded image or hardware-incompatible source could therefore discard an existing active upload or untaken READY texture before being rejected.

Both queues now perform the existing positive-token and phase checks first, followed by source preflight. Only a valid candidate reaches newer-revision invalidation and normal upload admission. Queue status, source identity, progress, token, active owner and untaken output remain unchanged if preflight fails. No replacement journal, texture or staging payload is allocated by the checks.

| Candidate | Preflight behavior |
| --- | --- |
| Failed map raster or decoded image | STATE error before replacement mutation |
| Width/height beyond device texture size | CAPABILITY error before replacement mutation |
| A whole source row beyond device staging allowance | CAPABILITY error before replacement mutation |
| Valid complete source | Continue normal revision acceptance, cleanup and admission |
| Stale/duplicate token in an ineligible phase | Return false before inspecting candidate completeness/capabilities |

Direct upload begin and queue submission share `GlMapUpload.validate` / `GlImageUpload.validate`, keeping their source and capability checks aligned. Begin validates again before its device journal is reserved and before queue replacement. The checks inspect bounded metadata and perform no pixel scan, colorization, decoding, native sampling or GPU allocation.

Explicit `invalidate(newRevision)` still abandons old work immediately. Connected map/image views retain their existing source-request invalidation policy, including invalidation before asynchronous preparation completes. The new guarantee applies while the lower-level queue still owns prior work; it does not restore something explicitly invalidated upstream. Hosts continue to compare desired revisions before taking or displaying old results.

## Admission and recovery boundaries

Metadata preflight does not reserve shared/device memory. The queue then admits the candidate's [complete known CPU/GPU/object footprint](FULL-STORAGE-ADMISSION.md) before accepting its token or disposing old work. Admission rejection preserves the earlier token/status/output. Old-owner cleanup or native setup failure after admission follows the existing FAILED protocol and retains unfinished ownership or device-retired/quarantined charges. A successful shape preflight does not promise that the request will fit current combined memory pressure.

Existing exact FAILED-token `retry` and connected retained-source recovery remain unchanged. A preflight-rejected candidate has not accepted a new token or failed the old job, so it needs no retry transition: supply a valid candidate or explicitly invalidate according to the host's current source decision. A WAITING queue remains WAITING after rejection; a READY result can still be taken at its old token if the host still wants it. Previously transferred map/image owners remain independent.

Original map captures/native identities, image dimensions/format, RGBA/alpha, texture filtering, whole-row upload budgets and memory ceilings are unchanged. No image is resized or sampled less densely to satisfy hardware or staging limits.

## Restored compilation evidence

The local checkout was synchronized to the exact previous GitHub commit and tree before changes. Compilation now covers the atlas queue's exact-baseline preflight/current-failure retry as well as these map/image guards. Earlier outage notes identify historical source-only review; they are superseded by this compilation evidence. No renderer contract, PowerShell runner, native adapter, GL context, upload, GPU readback or workflow was executed.

## Deferred verification

At renderer completion, keep each queue in WAITING, UPLOADING and READY while submitting a newer failed CPU source, oversized source or row-staging-incompatible source. Verify identical prior status object, token, progress, source identity, owned upload/result and byte/journal totals after rejection. Cover initial IDLE rejection, same-token WAITING rejection, stale/duplicate tokens with failed input and explicit invalidation before candidate availability.

Check exact hardware/staging boundaries for native maps and PNG/JPEG original images, direct-begin/queue agreement and original-context errors. Then inject cleanup/shared-admission/storage/row/finalization failures after accepted validation and verify FAILED/retry behavior without false rollback or released retirement charges. Retain/take old results and compare unchanged pixels/receipts; confirm no hidden resampling or resizing. Apply the existing atlas/readback preflight matrices on real Windows GL before enabling supported previews. Native parity and final editor integration remain pending.
