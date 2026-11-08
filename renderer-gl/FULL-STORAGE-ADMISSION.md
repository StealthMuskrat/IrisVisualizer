# Complete transfer storage admission

Renderer implementation, 2026-11-07. Image, map, scalar and readback transfers now admit their complete known CPU/GPU/object footprint before accepting transfer ownership. Previously, initial journals reserved CPU data while the first STORAGE step expanded GPU admission. A map/image/readback queue could discard earlier pending or READY output before that known GPU reservation failed. Compilation and source inspection are complete; renderer execution remains deferred.

## Known footprint before native storage

Existing completeness, original-context, hardware texture, row-staging and scalar grid-precision checks remain in place. Immutable metadata supplies the destination footprint without scanning, packing or changing original pixels/samples.

| Transfer | Retained CPU bytes | GPU bytes | Object slots | Journals |
| --- | --- | --- | --- | --- |
| Original image | `SourceImage.payloadBytes()` | Original RGBA image bytes | One texture | One |
| Native map | `MapRaster.payloadBytes()` | Original RGBA image bytes | One texture | One |
| Nonempty scalar full/recolor | `NoiseSurface.payloadBytes()` | `surface.size() * 12` | Two buffers and one VAO | One |
| Empty scalar full/recolor | `NoiseSurface.payloadBytes()` | Zero | Zero | One |
| Readback | `width * height * 4` | `width * height * 4` | One pixel-pack buffer | Work plus output |

Reservations fit alongside all live and retired ownership, under unchanged device/shared limits. There is no credit for old resources that replacement might release. Bounded automatic collection may still reclaim unrelated eligible retirement during admission.

The existing queues prepare new transfers before releasing accepted work or publishing a replacement token. Their initial admission now includes GPU bytes and object slots as well as CPU data. Rejection preserves exact prior status, token, progress and queue-owned pending/READY output. The same unspent token can be submitted after pressure is relieved. Explicit invalidation and asynchronous source-preparation policies retain their existing meaning; this change does not revive upstream-invalidated output or create a scalar replacement queue.

## Storage lifecycle and cleanup

Internal `reserveStorage` marks explicitly admitted storage as never started. `beginStorage` verifies device membership and records an irreversible transition before native allocation. Repeated or foreign transitions reject. Zero handles after a failed operation do not prove storage never began.

`discardUnstarted` requires this lifecycle proof, original-device registration, no dependents, no native scene owner, no fences and zero native handles. It can then release retained CPU data and parent source pins together without a GPU fence. Ordinary CPU journals cannot use this route. An admission/setup failure visits every newly owned journal/source and preserves its primary error with distinct cleanup failures suppressed.

Closing or cancelling before storage refunds unused CPU/GPU/object reservations immediately. The existing STORAGE steps create native storage from their admitted footprint and continue reporting actual storage bytes at that step. Storage creation, complete-row/scalar transfer, copy traffic, work/action/time budgets, state restoration and READY completeness checks are unchanged.

Started transfers use normal fenced retirement and remain fully charged until actual release. Readback work keeps its exact frame dependency through buffer retirement even if the caller closes both frame and job. CPU pixels move to the existing output journal only after complete read/copy coverage. Owned output handoff still admits its separate CPU owner before consuming READY pixels, preserving that output if handoff admission rejects.

Scalar recoloring independently retains exact source GPU geometry after destination admission. It still copies eight geometry bytes and transfers four color bytes per rectangle; full upload still transfers all twelve bytes. Source release follows queued-copy ordering and normal fenced retirement. Destination scenes own complete storage without GPU ancestry chains. Owned CPU surface overloads retain matching-coordinator input admission through destination admission and consume the fresh wrapper on rejection. Empty scalar scenes require no GPU allowance and preserve complete original sample/style receipts.

Admission cannot guarantee driver allocation success and does not account for all JVM/driver overhead. [World uploads now admit their complete storage separately](WORLD-STORAGE-ADMISSION.md), retaining incremental validation without later capacity expansion. [Atlas updates also admit changed pages and the output/framebuffer journals up front](ATLAS-STORAGE-ADMISSION.md). No original identity, requested extent, configured height, native/asset readiness or unsupported-preview gate changes.

## Prepared verification and current status

Five new GPU groups prepare one-byte-short full-peak rejection in IDLE, WAITING, unstarted, started and READY queue states; exact status/binding preservation; old/new byte-exact pixels and receipts; same-token recovery; exact-budget transfers; unused cancellation/closure refunds; and normal started retirement. Lowered device limits cover GPU bytes, journal slots and object slots, including rollback when readback work fits but its output journal does not.

Scalar cases prepare full/recolor admission, exact counters and copy/transfer totals, tracked-wrapper consumption, unchanged earlier frames and empty geometry on a zero-GPU device. Lifecycle cases prepare ordinary-journal, foreign, pinned, repeated-start and started-zero-handle rejection, source-pin refunds and abandoned transfer dependency cleanup.

Both compilation sets pass: 144 full Java 25/LWJGL renderer/validation files and 106 dependency-free files. All 148 prepared groups remain unexecuted: 105 CPU/context-free and 43 GPU. No renderer harness, GL context, shader, native comparison or workflow ran. Fault injection, genuine Windows/native/GPU validation, total-process memory/performance measurement, renderer completion and final editor integration remain pending. Unsupported previews remain disabled.
