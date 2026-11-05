# Shared admission through preparation-worker completion

Map colorization, atlas-frame preparation and translucent camera sorting now opt into the same `RenderMemory` coordinator as world preparation/cache and GPU journals. `GlMapView` and `GlWorldView` automatically pass their device's optional coordinator to the workers they create. This adds known-payload admission and ownership; it does not change native generation, rendering algorithms, clocks, area/height defaults or preview readiness.

## Job and completion owners

`MapRasterWorker`, `AtlasFrameWorker` and `WorldOrderWorker` each accept `(workerBytes, memory)` alongside their existing constructors. Existing constructors keep their previous local limits and use untracked holds. A participating submission admits its full known working reservation before changing revisions, cancelling active work or dropping worker-owned output. Failed admission preserves the prior worker state. As before, ordering submission consumes a fresh source scene wrapper even when rejected; an already-owned wrapper is rejected without closing its existing owner.

Every admitted job owns a `RenderMemory.Hold`. Successful publication retains an independent hold for its completion. Both handles share one account, so READY publication does not charge the same job twice. The job keeps its handle until its execution stack unwinds; closing/dropping/taking a completion cannot prematurely release the active reservation. Cancellation releases pending holds immediately and active holds only after unwinding. Setup, preparation and completion-publication failures release their owned handles without exposing partial output.

| Worker | Admitted peak | Retained completion charge after unwinding |
| --- | --- | --- |
| Map initial colorization | Native samples plus complete RGBA output | Samples plus complete raster |
| Map recoloring | Complete source raster plus replacement RGBA; source only for equal styles | Samples plus final raster |
| Atlas preparation | Original layout payload plus padded patches and copied pages | Same conservative source/plan payload |
| Camera sorting | Source scene plus 32 bytes per verified translucent face, capped by existing sort/working limits | Source scene plus final order array |

[Prepared scene material counts](MATERIAL-COUNTS.md) now size sort demand without a synchronous face scan. Valid jobs reserve actual complete key/depth scratch rather than the full configured scratch ceiling; invalid sort requests still report limits before array allocation. All existing local count, byte, scratch and aggregate limits still apply. These workers do not increase the shared 1 GiB ceiling. Each tracked job uses one bounded coordinator account, and each account supports at most 65,536 independent hold handles. Known source data may be conservatively charged in multiple subsystems; there is no cross-account array or source-identity deduplication.

## Taking and releasing output

The three CPU completion types now implement `AutoCloseable` and expose their `admission()` hold. Their legacy constructors remain available with an untracked hold. `take(expectedRevision)` transfers the worker's completion once and keeps its hold charged. Worker invalidation, replacement and shutdown do not close a previously transferred completion.

Keep a tracked completion open while using or handing off its output. Close it after the output is dropped or another admitted owner retains the required data. Closing a map/atlas completion releases its hold; closing an ordering completion also closes its source scene lease. Raw immutable raster/plan/order references do not independently extend this budget ownership. If they must outlive completion closure, explicitly retain the hold and any separately required scene lease first, then close those owners when done. Retaining a hold does not duplicate data or acquire a new scene lease.

`RenderMemory.hold(label, bytes)` creates a full reservation before starting work. `Hold.retain()` creates an independently closable handle without increasing the byte charge. After temporary work has unwound, `shrinkTo(retainedBytes)` can lower the shared reservation to retained output; it cannot grow. Last-handle close releases the account's byte charge and its account slot. Closing the coordinator still preserves all live holds and permits these decreases/releases.

Worker `reservedBytes()` reports worker-owned active/pending/ready payload according to its existing contract. Caller-transferred completions are absent from that local value, but remain visible in the coordinator's totals until their last hold closes. `WorldOrderWorker.reservedBytes()` now reports its active/pending/untaken order payload too; `GlWorldView.orderReservedBytes()` forwards that synchronized snapshot without GL work. Source-lease ownership and conservative replacement admission are unchanged.

## Atomic idle replacement

Map and atlas submission use `holdReplacing` to admit a new job while consuming worker-owned idle pending/ready handles atomically. If all handles for an old account are being dropped, their byte charge and account slot provide replacement credit. Duplicate handles are counted once. Handles from another coordinator are rejected, and failure leaves old handles/bytes/account slots untouched.

A ready output still sharing its account with an executing job supplies no release credit. The completion handle may be consumed, while the active job's independent handle keeps the original account fully charged until unwinding. Caller-transferred or explicitly retained handles likewise keep their charge and cannot be reclaimed by worker replacement.

The sorting worker also catches completion-publication failures, preserving latest-revision reporting and allowing later requests after normal cleanup. It drops its job reference before shrinking scratch to retained output; source cleanup must succeed before admission is released. See [prepared translucent centers](TRANSLUCENT-CENTERS.md) for CPU metadata and GPU transfer accounting.

Only idle payload whose ownership can be dropped without external cleanup belongs in this replacement operation. The ordering worker admits its new job before closing prior scene owners and deliberately uses conservative overlap rather than idle replacement credit. A budget failure therefore does not require destroying an old scene to make the replacement concrete.

All replacement metadata, iterator and account/hold objects are allocated before old handles are consumed. Failed account insertion removes the empty account. No callback, resource deletion or native/GL work runs under the coordinator lock. Replacement is bounded to 256 supplied handles and respects the existing account-count ceiling.

Invalid [view directions](TRANSLUCENT-CENTERS.md) are now rejected before sorting-worker admission or replacement. Pending/READY ownership and reservation totals survive rejection; direct preparation checks the same stable norm before scratch allocation.

## Camera-order reservation visibility

`WorldOrderWorker.reservedBytes()` counts full source/scratch reservations for active and pending jobs. Once a published job unwinds, it reports only the untaken source scene plus final order keys. READY before unwind counts that job once at its full active reservation. Taking a completion removes it from worker-owned totals; its hold remains visible in coordinator totals. Close/invalidation drop idle work immediately, while cancelled active stacks remain visible until unwind. Invalidation also prepares its next status before cancelling owned work, so status-allocation failure cannot first destroy the old result.

The source-cleanup overlap described above remains intentional: replacement first admits its full new reservation and account before disposing prior pending/ready scene owners. A single-account or insufficient-byte limit rejects without changing the old token/status/owner. With sufficient overlap, successful cleanup leaves only the new pending reservation. Active sorts remain subject to the local active-plus-new-request bound. Caller-transferred completions and independently retained holds supply no early release credit. No sort algorithm, camera identity, scene geometry, byte ceiling or source-lease rule changes.

Four additional synthetic groups prepare checks for pending/READY overlap rejection and exact-bound accepted replacement, already-owned wrapper protection, source-plus-key shrink, stale take, transferred completion/independent hold pressure, active local admission and cancelled-active charge through nonblocking close. Tests gate the private daemon executor through read-only reflection without patching worker state; the active case holds a source monitor until SORTING is observed. FIFO barriers separate publication from unwind. Expected twelve-face scratch/key sizes use explicit primitive widths, not the sorting implementation. These groups compile but have not run. All 129 production sources and four harnesses compile; 64 groups are now prepared across the four harnesses. Broader publication races, cleanup/status allocation failures, native/GPU behavior and final application integration remain deferred.

## Retrying scene-owning worker shutdown

`WorldOrderWorker`, `WorldPickIndexWorker`, `WorldSceneWorker` and `WorldRenderWorker` now distinguish their closed input gate from a successfully completed shutdown attempt. A cleanup failure no longer makes later close calls return just because new work is forbidden. Each attempt cancels/disposes worker-owned idle data, requests executor interruption, closes owned scene caches where applicable and publishes terminal metadata. Source cleanup, account refresh, executor shutdown, cache close and status errors preserve the first failure and suppress later distinct failures. Successful attempts become idempotent; failed attempts remain eligible for another close.

Executor interruption and cache closure continue even after an earlier cleanup step throws. These operations remain outside the worker lock, while state mutation/publication stays synchronized. New submit/invalidate operations and output transfer remain disabled after the first close begins, even if terminal-status allocation fails and the preceding metadata snapshot remains visible. The request revision is unchanged. This does not introduce blocking termination waits or force-release active job reservations.

An owned cache still retires its admission while transferred scene leases keep source/mesh payload charged. A successful close attempt means shutdown and cache retirement were accepted, not that active stacks or external leases have unwound. Sorting replacement retains its existing conservative source-cleanup overlap; index/scene/render preparation, identity checks and local/shared ceilings are unchanged.

Package-private executor-creation hooks support deterministic lifecycle validation; public construction still creates the same single daemon executor and thread names. Managed scene/render construction releases its newly opened cache account if executor creation throws a runtime exception, an Error or returns null. No new public scheduler or execution mode is added.

One additional prepared contract group supplies a controlled queued executor that fails its first shutdown with a specific runtime exception or Error. It checks pending sort/index source release, scene/cache-account cleanup despite executor failure, CLOSED admission guards, unchanged revisions, exact primary errors, second-close recovery, final idempotence and constructor-failure account release across all four workers. A completed transferred scene must keep its exact cache charge through failed shutdown and retry until final lease close. These assertions compile but have not been executed. Broader source-cleanup/status failure injection, active publication races and actual Windows native/GPU teardown remain deferred.

## Retrying preparation and pointer worker shutdown

The same retryable close rule now covers the other eight CPU workers: `ClientAssetWorker`, `ImageDecodeWorker`, `MapRasterWorker`, `NoiseSurfaceWorker`, `WorldVisualWorker`, `AtlasFrameWorker`, `WorldPickWorker` and `NoisePickWorker`. All twelve renderer workers close their input gate independently of shutdown completion, attempt executor interruption after owned-data cleanup errors, aggregate terminal-status failures and permit another close after an unsuccessful attempt. Successful shutdown requests remain idempotent. Public constructors retain their original single-daemon scheduling, thread names, default limits and coordinator policy; package-private executor creation hooks support controlled validation.

Pending/untaken worker-owned data is still dropped on close. Active cancellation remains nonblocking, with its existing reservation held until the request stack unwinds. Both pointer workers now clear their local request graph before releasing that hold, matching the preparation workers. Caller-owned indices, scalar surfaces and transferred CPU completions are not closed or uncharged by worker shutdown. No native sampling, file I/O, GL work, source reduction or new preview authority is introduced by close/retry.

One additional prepared contract supplies queued work to all eight families and injects the exact first-shutdown RuntimeException or Error. It checks terminal phase/revision, local/shared idle release, disabled take/invalidation, second-close success and final idempotence. Borrowed world indices/scalar surfaces must remain usable afterward; a transferred map completion must keep its charge through failure and retry until its owner closes. The asset fixture queues metadata without opening a client archive. These checks compile and remain unexecuted, bringing the total to 64 prepared groups. Broader active/close races, source/status-allocation failure injection, actual native producers, Windows/GPU teardown and final integration remain deferred.

## Connected GPU handoffs

`GlMapView` closes the taken colorization completion after map-upload admission or rejection. `GlWorldAnimation` closes the taken atlas completion after exact-baseline validation and atlas-upload admission or failure. `GlWorldView` already scopes the taken sort completion through draw-plan creation; its sorting worker now inherits the device coordinator as well. World-view setup also closes its newly created workers if construction fails.

The GPU journal is admitted **before** the CPU completion hold closes. This conservative overlap avoids an uncharged handoff gap. The shared allowance may reject a handoff even if a future deduplicating transfer could fit. A rejected handoff does not silently close a displayed caller-owned image or lower resolution; existing failure/retry contracts apply. Admitted CPU/GPU journals continue to retain their payload through fences, dependencies and quarantine. No additional context/thread or internal timer is created.

A directly constructed `GlWorldAnimation` still borrows the worker supplied by its caller; that caller must choose the matching coordinator. Lower-level raster/plan/order APIs remain usable independently, and callers using them must scope their completion holds through the actual destination admission or disposal.

## Detached readback ownership

The GPU readback path now offers [owned CPU handoff](../renderer-gl/READBACK-OWNERS.md) through direct and revision-checked `takeInput` methods. A device coordinator admits the full output before its device journal releases it; the independent input remains charged through device close until its final CPU owner closes. Setup/admission rejection preserves READY output. This adds no second image copy and retains existing conservative handoff overlap and raw-transfer external accounting.

## Remaining scope and validation

These additions automatically cover the connected map, atlas and sorting preparation paths when the device participates. [Picking and standalone visual/scene preparation](PICKING-MEMORY.md) now also provide optional shared admission. Native generation/sampling and producer scratch, caller-retained input catalogs/maps/plans, custom caches and escaped arrays still require separate host admission or further wiring. Java object/collection overhead, parser/decoder scratch, native caches and driver storage overhead are not measured by primitive reservations. Whole-application memory profiling and final editor integration remain pending.

All 101 independent renderer/OpenGL sources compile with Java 25 and LWJGL 3.4.1 (72 CPU, 29 GL). Eleven native adapters remain syntax-parsed/source-manifest checked only, pending genuine Windows API compilation/execution. No renderer tests, reservation/worker execution, native query, GL context, GPU upload or workflow ran for this batch.

At renderer completion, exercise shared account/byte/owner-count limits; no empty account after failed hold setup/insertion; exact-map and atlas reservations; sort scratch-to-output shrink; cancellation and executor failure; READY before unwind; take/close before unwind; replacement of pending/ready outputs; duplicate/shared/foreign replacement handles; no credit for active or transferred owners; stale tokens and repeated close. Verify every publication allocation-failure path, coordinator close with live holds, source-owner release failure without false budget release, worker shutdown with external completions and exact final zero charges. On real Windows GPU contexts, check shared-pressure handoff rejection, map/atlas/sort adoption, baseline mismatch, old images surviving failure, device/source overlap and delayed retirement. Compare actual native/GPU output before enabling supported previews. Renderer tests remain deferred until implementation is complete.

## Captured map style replacement

[Map recoloring](MAP-RECOLOR.md) now retains the complete old raster through preparation, charging its old RGBA alongside replacement pixels. Equal value styles reuse the CPU raster and reserve only that source. The job graph is dropped before the hold shrinks to final native samples plus RGBA. `MapRasterWorker.Completion.retainInput()` creates independent CPU ownership; consuming worker/view recolor inputs retain it through destination admission or rejection, with matching-coordinator checks. Runtime and reservation checks remain deferred.

## Owned atlas completion inputs

[Atlas completion ownership](ANIMATION-OWNERS.md) now exposes `retainInput()` and consuming GPU upload/queue overloads. Connected animation uses an independently retained plan through destination admission, checks tracked coordinator identity and preserves the existing conservative overlap. The worker drops its request graph before job-hold shrink/release. Prepared timing/page-sharing/limit/ownership checks compile without executing; runtime validation remains deferred.
