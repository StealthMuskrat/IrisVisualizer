# Complete CPU scenes and latest-request assembly

Renderer implementation, 2026-11-03. All 57 CPU renderer sources compile with Java 25. The separate [OpenGL backend](../renderer-gl/README.md) adds twenty-one compiling sources, without editor integration. Renderer runtime tests remain deferred until stage implementation is complete. Native world/model comparisons, cache/concurrency runtime verification, GPU drawing and visible Windows checks have not been executed for this batch. Unsupported previews remain disabled.

## Complete coverage before emission

`WorldMesher.plan` now exposes an immutable `WorldMeshPlan` count pass. It contains exact snapshot/model/visibility inputs, section extent, section limits and predicted output counts/bytes, with no world-section vertex/index arrays. `WorldMesher.assemble(plan, cancelled)` consumes the admitted counts and verifies that emission agrees. The existing convenience assembly API still performs both steps.

`WorldScenePlan.plan(models, visibility, cancelled)` partitions the entire captured native extent into explicit sections. The default edge is 16 blocks, and clipped edge sections cover nonmultiples exactly. Ordering is Y, then Z, then X. Negative coordinates/Y and distant long X/Z origins retain the existing integer-coordinate representation. The planner checks section count and tile limits before expansion, counts every section, and checks aggregate mesh/metadata/working limits before scene output allocation.

[Prepared material counts](MATERIAL-COUNTS.md) also retain surviving translucent and tint-required totals per section and complete scene. Section emission and complete scene construction independently verify these counts before publication; GPU validation and camera-sort admission can then consume metadata without scanning every source face.

[Picking size receipts](PICKING-SIZES.md) additionally record drawable sections and face/marker range groups. Complete scene construction rechecks them against actual mesh bounds and stored/grouped primitives, enabling constant-time background-index admission while preserving full section coverage.

Every successful plan covers exactly `snapshot.extent().cells()`, including the complete configured height. It does not reduce area, sample resolution, vertical extent, model detail or selected generation features to fit. An over-budget scene fails explicitly. Section size is an assembly parameter; the specified 128 × 128 preview default and source volume are unchanged.

The section count/emit passes now omit redundant cell-span records for fully hidden meshes. Cells emitting faces or explicit placeholder markers retain picking spans. All original cells, states and biomes remain in the native snapshot, including hidden and explicitly empty cells. This reduces cache metadata without dropping generated data, visible geometry or unsupported-content markers.

## Cache reservations and leases

`WorldSectionCache` retains up to 64 exact `WorldModels`/`WorldVisibility` epochs under one aggregate budget. Section keys contain the exact epoch, extent and section limits. Matching dimensions or input hashes alone do not mix different native snapshots, per-cell choices, visibility masks or atlas layouts.

`prepare(scenePlan, cancelled)` checks whether the complete planned scene plus all pinned prior scenes can fit before changing admission or evicting anything. Matching resident requested sections are counted once; only nonrequested unleased data can be evicted in the bounded commit. All metadata and the eviction iterator are allocated before commit. Cancellation is checked through preflight and before that commit. The compatibility overload without a supplier remains available. `reserve(sectionPlan, workingLimit)` either leases an existing immutable section or charges an incomplete reservation before new section arrays are allocated. Array bytes, face/cell/marker counts, entry count and shared source payload remain charged while reserved or leased. A reservation accepts only a successful mesh matching its admitted inputs, extent and usage receipt.

Unleased completed entries may be evicted in least-recently-used order. A single bounded scan removes eligible entries; pinned/reserved entries are never treated as free space. A duplicate incomplete reservation or insufficient combined pinned capacity reports `CACHE_BUSY`. Retained old epochs can coexist when the complete aggregate admission fits; otherwise release owners or explicitly change the requested budget/area, never bypass accounting. Reservations must belong to the currently prepared complete plan. See [FRAMES.md](../renderer-gl/FRAMES.md) for replacement and presentation ownership. Closing an incomplete reservation removes its charge; releasing the last lease on a completed section makes it eligible for reuse/eviction.

`clear()` succeeds only with no live tickets. `close()` retires all new admissions, immediately releases unleased entries, and leaves leased entries charged until their final releases. The last release also clears shared epoch/source references. Cache statistics expose closure, entries, leased sections, reservations, ticket count, source bytes, output bytes, metadata counts and epoch count. No GPU object or direct memory allocation belongs to this cache.

## Atomic scene results

`WorldScene.build(plan, raster, cache, cancelled)` requires a successful raster sharing the exact atlas layout. It reserves/builds or reuses each planned section, then checks full section count, covered cells and aggregate output counts against the admitted plan. The immutable `WorldScene` retains ordered section-local meshes, the native snapshot and the raster. It does not combine their integer origins into imprecise absolute float vertices.

The returned `WorldScene.Result` is an `AutoCloseable` owner of all section tickets. Keep it open while using its scene. Closing it clears that owner's scene reference; later `scene()` access fails. `retain()` creates an independent owner of the exact same scene and shared ticket bundle, capped at 65,536 live owners per bundle. Tickets release only after the final shared owner closes. Retain/close are synchronized and a failed wrapper allocation does not increment the shared count. A retained picking owner can keep the scene charged after its GPU owner retires; see [PICKING.md](PICKING.md). Java cannot prevent a caller from retaining previously obtained mesh/scene references, so consumers must stop using and retaining those references after releasing the owner. Arbitrary escaped references are outside cache accounting.

Failed or cancelled builds release every acquired ticket and publish no partial scene. Valid completed sections from an interrupted build may remain as unleased, bounded cache entries. They are reusable only under the same epoch/key. Structured failures preserve the source generation receipt; memory recovery is best effort and does not guarantee survival under process-wide exhaustion.

## Complete cleanup attempts

Final shared-result release and failed/cancelled scene assembly now visit every ticket in the owned bundle even when an earlier close throws RuntimeException or Error. The first release error stays primary; later distinct errors are suppressed. Indexed cleanup avoids allocating an iterator during unwind. If recording a newly acquired ticket fails, that ticket is still closed, with cleanup failure suppressed onto the original recording error. Try-with-resources keeps an existing build/cancellation failure primary when bundle cleanup also fails.

This preserves existing consumption semantics: a closed `Result` cannot be read, retained or adopted again, its reference count drops once, and a consumed ticket is not replayed. This is best-effort release of the whole bundle, not rollback of a partially failed cache/account update or a promise that every failing ticket was successfully released. Cache/shared charges follow actual release; no blanket uncharge, source substitution or memory-ceiling change is introduced. Internal cache/account failure recovery still requires deferred validation.

The existing cache-ownership group now also prepares cancellation, RuntimeException and Error after a completed section and a second incomplete reservation are both leased. It checks original-error identity, zero remaining leases/reservations, one reusable complete section, a subsequent full four-section build and final source/account/byte release. These cases compile without executing; the suite remains 21 world groups and 64 groups overall.

At renderer completion, additionally inject ticket-release failures at the first, middle and last positions for final shared-owner close and partial-build unwind. Verify every later ticket is attempted, primary/suppressed ordering and identity, independent retained owners, exactly-once reference drops, closed-result guards, and conservative charges after a failed cache update. Combine ticket-recording allocation failure with cleanup failure and original build failure with multiple release failures. These cleanup-failure paths are source-inspected only, not covered by executed assertions.

## One worker and one pending request

`WorldSceneWorker` owns one CPU worker executor and its supplied/default section cache. It starts no UI or GPU work. `submit(Request)` returns a monotonically increasing request revision, cancels the active job cooperatively, replaces the one pending job, and closes any previously owned untaken result. It never starts overlapping scene assembly jobs. Inputs are immutable snapshots supplied by the caller; generation itself is not implemented or scheduled by this worker.

The worker plans full coverage, builds admitted sections and adopts a result only if its revision is still current. Superseded/cancelled results are closed, and their failures cannot overwrite a newer request's status. `status()` reports queued/planning/building/ready/delivered/failed/closed state and structured problems. Here, READY means complete CPU scene assembly only; it is not engine parity, preview permission or GPU readiness.

`take()` transfers the latest owned completion exactly once, including its revision and result lease. After transfer, the caller owns the lease and must close it. A completion is current at the instant of transfer; a future UI/render consumer must recheck the revision when adopting it and stop using an old scene when replacing its request. Outstanding transferred leases cannot be silently revoked: they remain charged and can block a new epoch with `CACHE_BUSY` if combined capacity is insufficient. Otherwise old and new complete scenes can coexist during replacement.

Closing the worker cancels active/pending work, drops owned results, retires its cache and shuts down without waiting on a worker from the caller's thread. Transferred results remain the caller's responsibility; cache charges retire when those owners close. No callbacks into editor or renderer code execute under cache locks. Executor inputs retain at most one active and one pending request, rather than an unbounded edit queue.

## Limits and scope of accounting

| Resource | Default | Hard ceiling |
| --- | --- | --- |
| Scene section edge | 16 | 32 |
| Scene/cache sections | 8,192 | 65,536 |
| Scene/cache faces | 2,097,152 | Same |
| Scene/cache emitted cell records | 2,097,152 | 4,194,304 |
| Scene/cache placeholder markers | 1,048,576 | Same |
| Aggregate world-section packed arrays | 256 MiB | Same |
| Shared source plus resident/reserved section arrays | 512 MiB | Same |
| Active section tickets | 65,536 | Same |
| Retained/prepared exact epochs | 64 | Same |
| Active/pending CPU jobs | 1 / 1 | Same |

Scene and cache limits can be lowered independently; both must admit the complete scene. Existing per-section limits also apply. Shared source primitive payload is charged once per epoch, including native indices/masks, retained prototypes and the existing atlas source/base-level reservation. Every cached/reserved section's primitive output is charged separately. The working ceiling includes these shared sources and resident outputs; it is not an additional allowance above the mesh cap.

Object/string/collection overhead, plan metadata, captured archive bytes, caller-owned originals, incoming active/pending input bundles, escaped/obsolete results, native generator work, animation update scratch, direct staging and GPU memory remain outside this primitive-byte accounting. Counts bound metadata separately. These APIs do not confirm total application memory, change generation/preview defaults or bypass `PreviewReadiness`. The eventual application must reserve all simultaneously live generation/input/GPU resources together.

## Deferred stage validation

At renderer completion, verify complete nonoverlapping coverage of odd dimensions, clipped edges, full configured height, negative Y and distant origins. Compare count/emit output and exact native state/biome picking with standalone section assembly. Include empty/fully hidden cells and placeholder-only volumes; native snapshot contents must remain unchanged when redundant picking records disappear.

Check aggregate counts/byte limits before emission, exact cache keys/epochs, source bytes charged once, cache reuse, unleased LRU eviction, pinned/incomplete admission rejection and complete-scene fit. Exercise reservation completion mismatch, cancellation/allocation failure cleanup, duplicate close, ticket count limits, closure with live reservations/results, and final release clearing source references. Failed scene results must expose no partial geometry even if reusable cache entries remain.

Use controlled request barriers for supersession during planning, section construction and result adoption. Verify one active plus one latest pending request, no stale success/failure adoption, exactly-once ownership transfer, revision checks, aggregate overlap/epoch-replacement pressure and nonblocking shutdown. Repeated submit/take/close races must preserve every lease charge without leaking pinned entries or reviving a closed cache. Full runtime verification remains pending; no renderer tests were run for this implementation batch.
