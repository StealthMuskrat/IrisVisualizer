# Shared picking and standalone preparation admission

Picking indices/queries and the standalone visual/scene workers now have optional `RenderMemory` admission. This extends the coordinator beyond the connected world, map, atlas and sorting endpoints. Native block/state/biome identity, picking geometry/alpha behavior, configured area/height, rendering fidelity and preview readiness are unchanged.

## Index build and ownership

`WorldPickIndex.prepare(cpuOwner, limits, memory, cancelled)` consumes a fresh CPU scene lease on all outcomes after nonnull checks, following the existing index contract. The caller must retain a separate scene owner when sharing it. Count and local payload/working preflight run before a shared hold is acquired, and shared admission runs before hierarchy arrays are allocated. A shared limit failure returns a failed index with a LIMIT diagnostic and consumes/releases the fresh input lease. Closed coordinator and invalid ownership failures still propagate through the consuming boundary.

The peak includes the source scene, 60 bytes per hierarchy node, four bytes per admitted section and the four-byte build cursor when the hierarchy is nonempty. The build cursor is now included in the existing local index/working bounds too. After building, temporary index lists/cursor have unwound and the successful hold shrinks to source-scene bytes plus final hierarchy arrays. Original limits remain 16 MiB for index/build primitives and 512 MiB for scene plus index work.

`retainedBytes()` retains its hierarchy-array meaning. `admittedBytes()` reports the tracked source/index charge and remains nonzero while index closure waits for in-flight queries. Index close requests cancellation, prevents new queries and defers array/source release until the last active query leaves. Source-owner release precedes clearing arrays and closing the shared hold; failed source cleanup does not claim successful release. Failed/cancelled builds publish no partial index or native graph.

## Displayed atlas and pointer requests

A tracked synchronous `index.query` admits additional displayed atlas pages before alpha inspection. Pages already present by identity in the original scene raster are not charged again by that query, and repeated displayed page identities are counted once. The supplied raster must have the exact scene layout and be successful. Extra pages stay charged through cancellation, query completion and index-close races, then release as query ownership ends. Existing base-level alpha, face/marker/node limits and geometry rules remain unchanged. Global admission does not establish filtered/native pixel parity.

`WorldPickWorker(memory)` admits the whole request source—scene, index and additional displayed pages—before queueing it. This covers pointer requests while pending, including when the caller closes its frame or index before execution. The worker borrows the index/raster as before; closing the index remains an intentional cancellation and does not keep it open. A queued reference to a closed index may remain conservatively charged until its job unwinds even after that index releases its own arrays.

Pending pointer replacement uses the existing atomic idle-hold operation; active cancelled requests retain their charge until execution unwinds. Global rejection, invalid index/layout preflight or account exhaustion occurs before changing revisions or cancelling the old job. The no-argument pointer-worker constructor remains untracked. Use the same coordinator for the index, pointer worker and GPU/world endpoints to obtain combined coverage. Independent owners can conservatively count shared scene/index/displayed pages more than once.

`WorldPick` output contains detached receipts, bounded state/biome/face metadata and scalar hit/counter values, so pointer completion needs no source-array hold. The request hold ends after query execution unwinds; completed hit inspection continues without an index, raster or scene lease. Existing host revision/index-ID checks still govern adoption of detached hits. No native calls or GL operations are introduced by picking admission.

## Standalone visual preparation

`WorldVisualWorker(workerBytes, memory)` now uses the same job/completion hold pattern as the map/atlas workers. It admits the job's full declared working ceiling before submission, atomically replaces idle pending/ready handles, and keeps cancelled active work charged until unwind. Publication allocates status/completion metadata before transferring ownership. Failures in preparation or publication expose no partial result or lost completion handle.

Its `Completion` is now `AutoCloseable`, with a compatible two-argument constructor using an untracked hold. Taking a completion transfers its charge; replacement, invalidation and worker close do not close a caller-transferred completion. After execution unwinds, the hold shrinks to the result's conservative `knownWorkingBytes` usage. This can include source payload no longer independently needed by the output, so it is not an exact Java heap measurement. Callers keeping raw model/raster references must keep or retain the admission hold until another admitted owner retains them.

## Standalone scene preparation

`WorldSceneWorker(workerBytes, memory)` owns one shared account and section cache. The worker reserves active work, current source/cache coexistence and pending source inputs under its aggregate ceiling; the cache independently maintains retained/admitted output within that same account. Submission rejects shared/aggregate pressure before cancelling jobs, closing old output or changing revisions. Existing default/cache constructors remain available; aggregate pending/cache admission now enforces a 1 GiB default ceiling rather than allowing unchecked overlapping inputs.

`Request.inputBytes()` uses the same native indices, offsets, tint input, visibility, unique prototypes and atlas source accounting as scene planning. Request construction now requires exact snapshot/layout agreement between models, visibility and raster, so a foreign graph cannot hide outside its source estimate. No world traversal, mesh assembly or sampling occurs in that estimate.

A successful scene completion retains its existing `WorldScene.Result` ownership. Its cache charge remains after take and worker close until every independent scene ticket releases. This uses cache admission rather than a second completion hold. A supplied external cache retains its own admission policy; the cache constructor does not implicitly attach another coordinator. Use the managed constructor for automatic shared job/cache coverage.

`invalidate()` now immediately cancels current/pending adoption, drops untaken output and advances the revision without requiring a replacement. `take(expectedRevision)` transfers only the matching completion; legacy `take()` transfers the current revision. Complete-plan/cache bounds, native provenance, original configured height and failure atomicity remain in force.

## Visual-to-scene handoff

`sceneWorker.submit(visualCompletion, visibility)` consumes the visual completion on every outcome. A custom-limits overload preserves caller-supplied scene/section ceilings. It first retains a temporary source hold, rejecting an already closed tracked completion, then validates the successful visual result and exact scene inputs, admits the scene destination and closes the source handles. Tracked visual input requires a managed scene worker using the same coordinator; an unrelated or untracked destination is rejected. `Hold.belongsTo(memory)` allows this identity check without retaining source data or exposing account internals.

Destination admission and the source hold coexist conservatively. Shared pressure can reject the handoff, leaving the destination's previous work intact; the consumed visual completion then closes. Keep an independently retained source hold first if its raw data must survive rejection. Existing user-selected resources, native source receipts and clock/raster identity remain authoritative. This route does not connect editor presentation or enable a preview.

## Remaining scope and deferred validation

Shared admission can now cover the implemented CPU worker families, index lifetime and device journals when their matching optional constructors are used. Native generation/sampling/producer scratch, caller-owned input catalogs and arbitrary escaped arrays/graphs still need explicit host accounts; custom external caches keep their own policy. Java object/collection overhead, recursive stack frames, parser/decoder scratch, native caches and driver overhead remain outside primitive estimates. Whole-application memory profiling, genuine native producers and final integration remain pending.

All 101 independent CPU/OpenGL sources compile with Java 25 and LWJGL 3.4.1 (72 CPU, 29 GL). Eleven native adapters remain syntax-parsed/source-manifest checked only; genuine pinned Windows API compilation/execution is pending. No renderer tests, picking/worker/admission execution, native query, GL context, GPU operation or workflow ran for this batch.

At renderer completion, cover index exact/zero/over limits including cursor bytes, shared build failure/cancellation, shrink after build, empty indices, index-close versus active/pending queries, duplicate and changed displayed pages, wrong/failed raster layouts, source cleanup failure and final zero charge. Verify pointer admission rejection preserves previous work, pending replacement credits only idle handles, active requests stay charged, closed index requests fail without stale adoption and detached hits retain no source arrays.

Exercise standalone visual setup/preparation/publication failures, take/close before unwind, retained completion handles, replacement and closed-coordinator cleanup. Cover scene source estimates, foreign input rejection, pinned old cache coexistence, shared/aggregate rejection before mutation, invalidation without replacement, stale/current/duplicate takes, cancelled planning/building and scene leases surviving worker shutdown. Test visual-to-scene success and rejection with matching/foreign/untracked coordinators, conservative overlap, custom limits and retained source ownership. Genuine Windows GPU/native comparisons and final editor routing remain deferred; unsupported previews stay disabled.

## Primitive range arrays

[World picking ranges](PICKING-RANGES.md) now add original-order face/marker bounds and per-section offsets to the same preflight, retained-index and pointer-request byte estimates. All arrays are admitted before construction, remain live through in-flight queries and release with the original index/scene owner. The existing 16 MiB index, 512 MiB combined preparation and shared coordinator ceilings remain unchanged. Both former Options constructors enable bounded range culling, while an explicit exhaustive mode supports end-stage comparisons. All 128 CPU/OpenGL sources compile; no renderer tests or native/GPU/worker execution ran. Unsupported previews stay disabled.

## Admitted index-worker transfer

[Background index preparation](INDEX-WORKER.md) now retains the worker peak hold directly in successful index ownership, keeping the original job handle until unwind and then shrinking to actual retained source/arrays. Idle pending/untaken output can supply atomic replacement credit; active/shared handles cannot. Completed indices survive worker shutdown until the caller closes them. Frame-based pointer submission independently charges the exact displayed CPU atlas through existing request admission and requires matching device/index/query coordinators. Both worker families enforce aggregate active/pending ceilings even without tracking. All 129 CPU/OpenGL sources compile; no index/query/worker/native/GPU execution or tests ran. Unsupported previews remain disabled.
