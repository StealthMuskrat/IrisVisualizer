# Prepared picking-index sizes

Current preparation defaults to [source-backed range reuse](SOURCE-RANGE-REUSE.md), with explicit COPY storage retaining flattened range arrays. Admission uses verified scene-level counts and the selected storage mode. Both 144/106-file compilation sets pass; all 129 prepared groups remain unexecuted. Native/GPU validation and final integration remain pending; unsupported previews stay disabled.

## Verified complete-scene metadata

`WorldScenePlan.Usage` adds `drawableSections()`, `faceBatches()` and `markerBatches()`. Planning accumulates these counts while it already visits section count receipts. A section is drawable when it emits at least one face or diagnostic marker. Each nonempty face/marker family contributes its own contiguous groups of at most 128 primitives per section, including final partial groups. Ranges from separate sections are never combined.

World mesh publication now also checks that drawable bounds are present exactly when faces/markers exist, and that face/cell/marker collections agree with usage. Complete scene construction independently sums actual bounds presence, stored face-bound and marker-bound groups, then verifies the aggregate against the plan alongside its existing coverage/material/payload checks. Cached sections undergo the same exact-plan checks as emitted sections.

Usage rejects negative/impossible category counts. The previous seven/nine-argument scene-usage constructors remain available with zero added counts; successful private scene factories produce the complete verified receipt. No extra face/marker/voxel array is added by these counters, and their bounded object metadata retains its existing external accounting boundary. Native state/biome identity, geometry, prepared bounds and picking order remain unchanged.

## Primitive size calculation

The SOURCE default allocates only the section hierarchy and build scratch below; COPY additionally allocates each applicable range/prefix row. Let `S` be all scene sections, `D` drawable sections, `F` face groups and `M` marker groups. There are `2D - 1` hierarchy nodes when D is nonzero, otherwise none. Retained index bytes and build-only scratch are calculated from the same helpers during worker admission and actual preparation:

| Payload | Primitive bytes |
| --- | --- |
| Section hierarchy | 60 × node count: six doubles and three integers per node |
| COPY face and marker range bounds | 24 × (F + M) |
| COPY face section offsets, when F > 0 | 4 × (S + 1) |
| COPY marker section offsets, when M > 0 | 4 × (S + 1) |
| Temporary drawable-section IDs | 4 × D |
| Temporary hierarchy build cursor, when D > 0 | 4 |

In COPY, the offset arrays cover all sections, including empty ones. This is significant for sparse scenes: one drawable section inside many empty sections still needs the applicable complete section-offset family. A single-section scene containing one face group and no markers needs 60 retained index bytes in SOURCE or 92 in COPY, plus eight build-only bytes in either mode, before adding its complete source payload. A wholly empty scene needs no index primitive payload and reserves its source only. Java array headers, objects, collection/recursion overhead and unrelated work remain outside these primitive totals.

## Worker admission and preparation

`WorldPickIndexWorker.submit` reads this immutable receipt through the existing metadata-only reservation helper. It performs no synchronous section/face/marker traversal, vertex scan or hierarchy construction. For a valid job it reserves complete source plus the calculated retained/build arrays, within the existing configured index and combined working ceilings. It no longer adds the whole default 16 MiB allowance to every small job.

Source-only overflow still rejects before changing worker state. Requests exceeding index/combined preparation limits retain their existing worker-side structured LIMIT result before main index arrays allocate. Invalid requests do not receive a larger allowance or truncated index. Active plus pending local/shared admission, fresh-owner consumption, already-owned wrapper rejection and latest-revision adoption retain their existing contracts.

Actual preparation still counts sections and ranges on the CPU worker, checks them against the scene receipt, and independently checks the resulting peak against its previously admitted source/index reservation. A mismatch reports ASSIGNMENT before hierarchy/range allocation. Direct preparation uses the same size helpers and ceilings. Successful preparation builds the section hierarchy. SOURCE reads existing immutable per-section face/marker bounds through its source lease; COPY copies those prepared leaves and independently builds internal unions.

Temporary IDs/cursor unwind before reservation shrink; completed indices retain source plus actual index arrays. Cancelled active work stays charged until unwinding, and transferred indices keep their independent source/admission ownership through active queries and close. Smaller reservation slack changes which valid jobs can coexist, without raising the 16 MiB index, 512 MiB source-plus-index or 1 GiB worker ceilings. No timing or total-process memory improvement is claimed.

## Deferred verification

At renderer completion, independently count drawable sections and per-section face/marker groups for wholly empty, one-face, marker-only, mixed, hidden, sparse and maximum full-height scenes. Cover 127/128/129 primitive boundaries, empty sections between drawable sections, cached reuse and all scene/mesh count/bounds mismatch guards. Compare exact retained/peak formulas with actual array lengths, including absent range families and complete section-offset arrays.

Exercise old/new usage constructors, source-only overflow, exact and one-over index/combined/shared/local budgets, jobs that fit actual demand but not former slack, invalid requests failing before allocation and rejected replacement preserving previous state. Verify setup/cancellation/publication failures, scratch shrink after unwind, READY/take/close races, source closure during queries and final source/index charges. Compare resulting hierarchy/range arrays and sufficient-budget hit/miss/alpha/clip/tie results against the previous complete path. Native/GPU comparison and final editor integration remain pending; no renderer-stage tests ran for this batch.

## Exact internal-range tree demand

[Original-range hierarchies](PICKING-RANGE-HIERARCHY.md) extend the receipt with face-bearing and marker-bearing section counts, Sf and Sm. In COPY, internal bounds add 24 × ((F − Sf) + (M − Sm)) bytes, plus 4 × (S + 1) for each family with at least one internal node. Planning, completed-scene emission and actual index preparation verify these counts independently. Worker submission still computes exact demand from metadata only; sections with a single group reserve no internal arrays. Explicit linear builds omit the added demand. Prior Usage constructors remain available for legacy receipts, while complete scenes use the verified full counters. Existing 16 MiB/512 MiB/worker/shared ceilings remain intact, and no runtime assertion has executed.

SOURCE admits no duplicate range arrays or prefixes, even when hierarchy traversal is enabled. Actual preparation also verifies stored internal-node counts before allocation. See [source range reuse](SOURCE-RANGE-REUSE.md) for selected-mode formulas, ownership and current deferred coverage.
