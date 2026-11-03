# Background selected-client capture and catalog ownership

Renderer implementation, 2026-11-03. The 106 CPU/OpenGL Java sources compile on Java 25 with LWJGL 3.4.1: 75 CPU and 31 GL. Twelve native adapter sources are syntax-parsed and source-manifest checked only; genuine pinned Windows API compilation remains pending. No file-capture, scheduling, admission, native or GPU execution tests ran. Renderer tests remain deferred until implementation is complete. Unsupported previews remain disabled.

## One explicit selection, one background reader

`ClientAssetWorker.submit(new ClientAssetWorker.Request(selectedArchive))` queues the existing read-only `ClientAssets.load` operation on one daemon thread. Request construction normalizes the path without opening it and requires the exact `BlockModels.PROFILE` version. The default constructor uses the existing capture limits; the full request accepts lower limits and an explicitly matching version. Filename/version inference, installation scanning, downloads, native/client class execution and readiness changes are absent.

The worker has one active capture and one replaceable pending selection. Submission performs metadata/admission checks first. Rejection preserves the old revision, pending request, completion and active cancellation flag and starts no file I/O. Accepted submission cancels obsolete active work cooperatively, replaces pending work and drops an untaken old catalog. The next capture starts only after the old operation, including private scratch cleanup, returns. A slow file-system call cannot be preempted; there is no second overlapping reader.

Every accepted selection gets a monotonically increasing revision. `invalidate()` immediately advances it and drops pending/untaken output without requiring a replacement archive. A load completing after invalidation, close or replacement cannot publish. Repeated polling performs no I/O. Selecting the same path again is an explicit reload/retry, so changed bytes at that path are captured again; the worker does not guess whether the file is unchanged.

`status()` returns immutable phase, selected path, bounded problems and an optional detached asset receipt. It retains no catalog, stream, archive index or asset byte arrays. `READING` covers the whole existing copy/hash/ZIP/version/CRC load; it is not a byte progress report. Failed loads stay failed until submission or invalidation. I/O failures expose their bounded reason; worker and allocation failures expose a bounded class-based diagnostic. Partial catalogs are never published.

The original loader still captures the selected archive into bounded private scratch, checks original identity/size/modified time through copying, validates central-directory/entry metadata, CRC and exact declared version, and removes only that private capture. Later source edits/deletion do not mutate an already detached catalog. Scratch cleanup must succeed before a load returns successfully; cancellation/failure retains the loader's existing cleanup rules. No pack bytes or recovery/history files are created.

## Admission before file I/O

`ClientAssets.workingBytes(limits)` now exposes the known primitive peak for three sequential phases:

| Phase | Admitted primitive payload |
| --- | --- |
| Archive copy/hash | 64 KiB transfer buffer |
| ZIP preflight | Up to 32 MiB central bytes, up to 65,557 tail bytes, plus 76 ZIP64 header bytes; each archive-sized component is capped by the archive limit |
| Catalog load | Captured asset-byte ceiling plus 64 KiB version buffer |

The maximum of these phases is reserved, rather than their sum. The central-directory phase matters even when captured-asset limits are deliberately tiny: those limits do not restrict unrelated archive metadata. Default reservation is 256 MiB plus 64 KiB. No existing archive, asset, entry, path, model or texture ceiling is increased.

The worker's default/hard local allowance is 1 GiB and may be lowered. Active and prospective pending capture reservations must fit together before acceptance. An idle untaken completion can be replaced; cancelled active work remains charged until its stack unwinds. `reservedBytes()` includes active/pending peaks and untaken completed catalog bytes, avoiding a second local charge while an active operation still owns that same published result. Transferred owners are external to this worker's local allowance.

`ClientAssetWorker(workingBytes, memory)` optionally participates in the common `RenderMemory` coordinator. Admission precedes executor scheduling and every file operation. Atomic idle replacement credits only fully released pending/completion holds. Active, independently retained or already transferred catalogs continue to consume shared bytes and account slots. A failed shared admission cannot cancel previous work. After loading and publication unwind, the capture hold shrinks to actual encoded catalog bytes; temporary preflight/copy bytes are no longer charged. No assumption is made that another caller has stopped using its catalog.

These are known owned primitive payload estimates. JDK ZIP index/decompressor/native allocations, JSON/parser/string/collection objects, hashing implementation overhead, disk scratch and file-system caches are separately bounded or external and remain outside this byte total. This is not complete process memory profiling or a physical-memory guarantee.

## Exactly-once transfer and regeneration

`take(expectedRevision)` returns a `Completion` exactly once for the current selection and moves status to `DELIVERED`. A stale token, invalidation, close or missing completion returns empty. The host must still adopt only the current selection under its own ordering; a later revision cannot revoke an already transferred owner.

The completion owns `RenderInput<ClientAssetSnapshot>`. `assets()` borrows its immutable catalog. `retain()` creates another independent completion/input handle; both must be closed. `input()` is the existing consuming transfer handle, not another owner. Closing the worker, selecting another archive or invalidating does not close caller-transferred completions. Closing an input clears its catalog reference; another retained owner keeps the shared charge live until its own release. Raw escaped catalogs require an equivalent live owner throughout use.

The native capture bridge now accepts these owned catalogs directly through `NativeCapture.models(context, RenderInput<ClientAssetSnapshot>, ...)` and `weighted(...)`. Both consume the supplied wrapper on every outcome after the nonnull input check. They retain a temporary source handle, require the same shared coordinator for tracked inputs, and retain the catalog through native context checks, source capture and destination request admission. Rejection/failure also consumes the passed wrapper: retain a separate owner before a call when retry or later regeneration needs the catalog.

The raw catalog overloads remain unchanged for callers that admit/retain catalogs themselves. No native engine/session, RNG, chunk or provider ownership is acquired by the owned overloads. Their outputs still contain detached ready/model requests, and genuine Windows API/native fidelity validation remains pending. The loaded source catalog and new capture request may be conservatively charged simultaneously; there is no cross-account byte deduplication.

A typical host retains one catalog completion for its selected asset epoch, passes an independently retained input to each native capture, then consumes the resulting request through the existing world worker. Keep the catalog epoch owner until the last borrowed user releases it. Changing asset selection invalidates relevant downstream jobs separately; an asset revision or successful version check is not proof of generation/native rendering readiness.

## Shutdown and deferred verification

`close()` cancels pending/active work and drops an untaken completion without waiting for file I/O. The active hold remains visible until its actual unwind, including private capture cleanup. The executor is shut down; caller-transferred owners remain valid and charged. Polling after close returns detached CLOSED status. Queue-start and publication-allocation failures release their owned holds rather than leaving a busy worker or unowned catalog.

At renderer completion, verify pinned-version/CRC/archive failures and complete asset immutability, source changes during copy, slow I/O/cleanup cancellation, latest-only scheduling, same-path reload and stable failure/retry. Cover every pending/active/READY/DELIVERED invalidation and close boundary, stale takes, independent retained completion lifetime, and status graph isolation.

Verify exact/lower/over local/shared limits, tiny catalog limits with large valid central directories, account pressure, closed coordinator, rejection before any I/O/cancellation, idle replacement credit and cancelled-active retention. Check publication/take/shutdown races, source hold shrink only after load unwinds, failed setup/allocation cleanup, owned native capture with closed/foreign coordinators and rejection with retained retry ownership. Compare native/GPU results later without treating successful catalog loading as preview authorization. No tests ran for this batch.
