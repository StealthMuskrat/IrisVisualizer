# Shared renderer memory admission

`RenderMemory` coordinates known payload reservations across participating renderer owners. `WorldRenderWorker`, its leased `WorldSectionCache`, `GlDevice`, picking indices/queries and the CPU preparation workers can now use the same coordinator. Local limits continue to apply. Shared admission is optional in existing constructors and does not enable a preview, change native generation or establish a physical RAM/VRAM guarantee.

## One ceiling, explicit owners

The default/hard byte ceiling is 1 GiB and can be lowered to zero. The default account bound is 64, lowerable or configurable through a hard 256. Every account has a bounded label, retained/admitted bytes and a covering reservation. Its charge is `max(retained, reserved)`: the reservation includes the retained payload within that one exclusive subsystem account. Different accounts add their charges. Independent owners must not share an account merely to overlap unrelated reservations.

`Account.retained`, `reserve` and the atomic two-value `set` reject before changing byte totals if demand exceeds the shared allowance. Metadata/count limits and a closed coordinator also reject new admission. Snapshot methods expose immutable totals and bounded owner metadata; the coordinator retains no world, asset, scene, native handle or GL resource graph. `Stats.retainedBytes` and `reservedBytes` describe overlapping quantities and must not be added to calculate the charge.

The coordinator is synchronized. It executes no callbacks and acquires no worker/cache/context locks. Owners may enter it from their normal locks, while GL operations remain on the device's original context thread. Rejection does not block waiting for another owner or trigger unrequested eviction, reduced world area, height truncation or lower texture/map resolution.

## World preparation and leased cache

Construct `WorldRenderWorker(workerBytes, memory)` to opt in. Before cancelling old work, dropping completion or advancing the revision, submission reserves the new request's working ceiling and the active job/cache plus new frozen input, whichever is larger. Shared rejection preserves the previous job, revision, pending request and completion. Cancelled active work remains reserved until its stack unwinds.

When a job starts, it retains at least `max(job.workingBytes, residentCacheBytes + frozenInputBytes)` for its whole execution. This accounts for the original source coexistence peak even when the job will fail its later phase admission. A pending replacement adds its retained input, and its own working ceiling is also admitted. The worker drops only the active/pending reservation after those owners unwind; cache payload remains charged independently within the same account. Existing phase budgets still deduct pinned old scenes, masks and external catalog/raw inputs.

`WorldSectionCache(limits, account)` is also available for a separately managed cache. The cache exclusively owns that account's retained/admitted byte value; its host may set a covering job reservation. Do not pass the same account to another cache or unrelated payload owner.

Before mutating admission or allocating any section output, cache preparation charges all next-epoch sources, existing section payload and every missing section's planned output. Existing unleased payload is conservatively included until eviction actually releases it. Shared rejection therefore leaves old cache admission/entries untouched, and can reject a transition that would require releasing unleased data first. The normal world worker retires unleased prior data before visual preparation.

Missing output stays admitted as sections are allocated. Moving bytes from the future-section hold to an actual reservation does not change the aggregate charge. If an incomplete reservation is released or a prepared section is evicted, its planned bytes return to the hold until the admission is replaced, cleared or retired. No second shared admission is attempted halfway through output allocation.

Taking a completion does not release its section/source charge. Independent scene, picking and GPU leases keep their cache tickets pinned. Closing the worker/cache retires its admission and releases unleased entries, while still-leased data remains charged until the last ticket closes. A closed cache's account can therefore outlive the worker. `WorldRenderWorker.memoryUsage()` exposes that account's current metadata, including its covering job reservation. Existing `cacheStats()` continues to describe resident slots/epochs; it excludes not-yet-allocated prepared output held by shared admission.

## GPU device and retirement

Construct `GlDevice(limits, memory)` on the real owning context. It admits native staging before `memAlloc`; construction failure rolls back its account. Every subsequent device reservation includes GPU storage, retained CPU source/material/page/image payload and staging. All scene, map, target, atlas-frame, draw-plan and readback journals on that device participate through the same existing allocation path.

Shared admission precedes publishing a new allocation journal or expanding a validated scene journal. Failed journal insertion rolls back its shared charge. Moving already-admitted CPU page bytes between journals does not alter the aggregate. No GL storage is created to satisfy a rejected device reservation.

Retirement, pending fences, dependencies and quarantine keep their charges. Collection decreases the shared charge only after actual deletion and CPU-owner release. If teardown or deletion fails, the remaining journals and staging remain charged for retry. Successful device close frees staging and retires its empty account. A source scene that is also pinned in a participating CPU cache is conservatively counted by both cache and device; there is no cross-account source-identity deduplication.

`GlDevice.memory()` exposes the borrowed optional coordinator for host orchestration. It does not change thread/context affinity. `GlMapView` and `GlWorldView` now pass it to their map, atlas and ordering workers; see [WORKER-MEMORY.md](WORKER-MEMORY.md).

## Closing admission and external work

`Account.close()` seals growth beyond its existing admitted charge. It does **not** erase live retained/reserved bytes. Explicit decreases and moves within the existing charge remain possible, so admitted work can unwind. The account disappears only when both values reach zero. `RenderMemory.close()` similarly prevents new accounts and aggregate growth while retaining live charges; it does not dispose any owner. Close actual workers, image/scene leases and GPU resources through their own contracts.

For a separately owned native or CPU task, a host may open an account and reserve its known peak before starting it. It must keep that reservation until work unwinds, then change to the retained output charge for as long as it owns the output. On failure, release only after temporary data and retained output ownership have actually ended. Account disposal alone is not evidence of resource release.

Automatic coverage now includes world preparation/cache, device journals and connected map/atlas/sorting preparation. [Owned completion holds](WORKER-MEMORY.md) preserve charges through transfer, and the GPU views close them after destination admission. [Picking and standalone visual/scene workers](PICKING-MEMORY.md) now have matching optional admission, including retained indices, pending pointer sources and visual-to-scene handoff. Native generation/sampling, caller-held input catalogs/maps/plans and escaped array/raster references still need host-owned accounts or further integration. Device-side adopted maps/orders/animation/readback are charged once they enter their journals. Java object headers/collections, parser/decoder scratch, native library caches, driver overhead, unrelated processes and unknown native allocations remain outside known primitive estimates. Final application admission and profiling remain pending.

## Deferred stage verification

All 101 independent CPU/OpenGL sources compile with Java 25 and pinned LWJGL 3.4.1 (72 CPU, 29 GL). Eleven native adapters remain syntax-parsed/source-manifest checked only; genuine pinned Windows native API compilation/execution is pending. No renderer tests, reservation concurrency execution, GL context, allocation/fence execution, native query or workflow ran for this batch.

At renderer completion, verify zero/exact/over-limit demands, account-count/label bounds, atomic rejection, overlapping reservations and snapshot totals. Race independent admissions and closure; check shrink/within-reservation moves after retirement, repeated zero updates and delayed account removal. Exercise concurrent world/device peaks, active cancellation plus pending replacement, failed setup, invalidation, pinned old scenes, prepared missing outputs, failed section reservations, clear/retire and worker close with external tickets. Prove cache rejection does not evict/change prior admission and every planned-to-actual transition preserves total charge.

On genuine Windows contexts, check staging failure rollback, journal/expansion rejection before GL storage, source sharing conservatism, map/atlas/readback journals, CPU page charge moves, retained images, fence timeouts, quarantine, deletion/teardown retry and final zero charge. Include closed-coordinator cleanup and wrong-context failures without false release. Profile actual process/native/driver peaks separately before final editor integration. Unsupported previews remain disabled.
