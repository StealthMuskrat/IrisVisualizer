# Owned atlas plans and prepared animation contracts

Renderer implementation, 2026-11-05. Atlas preparation completions can now retain independently owned CPU plan inputs, and the GPU upload/queue paths consume them through destination admission. The connected world animation controller uses this handoff. All 129 production sources plus four harness sources compile with Java 25 and LWJGL 3.4.1 (133 total). No renderer test, animation/worker execution, native query, GL context or workflow ran. Unsupported previews remain disabled.

## Independent CPU plans

| API | Ownership contract |
| --- | --- |
| `AtlasFrameWorker.Completion.retainInput()` | New independent `RenderInput<AtlasFramePlan>` over the completion's existing hold |
| `GlAtlasUpload.begin(displayed, input)` | Consume supplied input through device admission or rejection |
| `GlAtlasQueue.submit(sceneRevision, frameRevision, displayed, input)` | Consume supplied input through queue/device admission, including stale/failed submissions |
| `input.retain()` | Share the same CPU admission in another independently closable wrapper |

No source pixels, patches or page arrays are copied by these ownership helpers. Retained inputs can outlive completion closure and keep the full admitted source/plan payload charged. Closing the coordinator preserves live charges until final input close. The compatibility constructors continue to provide untracked holds; raw plan overloads remain available with externally scoped admission.

Tracked GPU inputs must belong to the same coordinator as their destination device. Rejection occurs before queue observation or destination upload allocation. Every consuming overload closes the supplied wrapper on success, stale rejection, context/coordinator error or exception, while an independent retained owner survives. Keep such an owner before consuming an attempt if another retry is needed. A borrowed plan reference alone does not extend admission.

`GlWorldAnimation` now hands its taken completion to the queue through a retained input. This preserves its previous conservative CPU/device overlap and additionally checks coordinator identity at the connected handoff. Directly supplied workers with tracked foreign admission cannot silently transfer work into a different device budget. Untracked compatibility remains explicit external accounting. Exact displayed-raster baseline, scene/frame revisions, page sharing, upload budgets and old completed-image ownership remain unchanged.

The atlas worker drops its active request reference before shrinking/releasing the job hold after unwind. A failed/cancelled request therefore no longer remains strongly referenced by that job variable after its admission is released. Successful plans retain their ordinary full output charge through independent completion/input owners. No memory ceiling or source/atlas identity changes.

## Worker validation before atlas capture

`AtlasFrameWorker.validateDestination(destination)` checks the actual closed gate, next-revision availability and tracked coordinator identity under the worker lock. A tracked worker requires its exact coordinator; untracked workers retain explicit external-admission compatibility with either a tracked or untracked destination. Validation creates no account, submits no task, consumes no revision and never cancels or takes pending/ready work. It does not reserve future bytes or claim exclusive ownership. A closed coordinator or later memory pressure still fails through ordinary job admission.

`GlWorldAnimation` validates the live scene/context, positive scene revision and borrowed worker before constructing its atlas queue or capturing the initial GPU atlas. Closed, exhausted and foreign tracked workers therefore fail before any atlas journal, page reference or scene dependency is acquired. A tracked worker cannot target an untracked device; an untracked worker remains permitted with externally scoped CPU admission. The connected world view already creates workers with its device coordinator and keeps that policy.

The worker need not be idle: an exclusive worker can still be unwinding cancelled work from a previous world. The host must not submit unrelated jobs or close it concurrently with controller use. Validation is a snapshot; later worker closure, revision changes, coordinator closure and admission failures retain the existing explicit failure/adoption guards. Construction rejection leaves borrowed workers and scenes caller-owned and performs no invalidation or shutdown.

The existing retained-completion contract group now also prepares deterministic destination checks in idle, queued, READY, transferred, closed and exhausted states. It verifies status identity, next token, task queue, reservations and account/byte snapshots after rejection, successful execution/take of the preserved plan, independent transferred lifetime and untracked compatibility. The controlled executor starts no thread; its jobs run only when the deferred harness is explicitly executed. Group count remains 11 for animation and 64 overall. No assertions ran.

At renderer completion, additionally reject closed/exhausted workers, tracked foreign workers and tracked workers with untracked devices on a real current GL context. Check unchanged scene ownership, device journals/dependencies, worker status/charge and queued/READY output after constructor rejection, including under account pressure. Confirm matching and untracked compatibility, cancelled-active reuse, later close/admission failure and ordinary clock/plan/atlas adoption. No fake GL device is used as a runtime substitute.

## Prepared animation suite

`AnimationCpuContracts` adds 11 groups to the three earlier harnesses. With the subsequent sorting, shutdown-recovery and world/map/image boundary groups, 64 groups are now prepared in total. Its sources compile; no assertion has been executed or claimed to pass. The default Windows validation runner compiles only and now includes four suite classes. At renderer-stage validation, explicit `-Execute` launches each suite in its own JVM and stops on failure. The runner remains inspected only on this host.

| Group | Prepared expectation |
| --- | --- |
| Animation/texture metadata | Full static sheets, implicit tile schedules and retained blur/clamp/mipmap/bias settings |
| Zero/lone schedule entries | Static top-left upload even when one entry names another tile |
| Integer scheduling | Repeated entries, explicit duration boundaries, exact `Long.MAX_VALUE` modulo, fractional metadata and integer-only pixel interpolation |
| Metadata/clock guards | Invalid frame time/index/layout/fields and invalid clock rejection |
| Canonical pixel inputs | Zero/full blend endpoints and repeated tile normalization |
| RGBA interpolation | Independently known half-red/half-blue straight RGB and alpha, including all extruded gutter pixels |
| Same-pixel clocks | Fractional/cycle clocks coalesce while exact baseline identity remains required |
| Copy-on-write pages | Only the animated page changes, static page sharing, grouped patches, old pixels and exact source/patch/copy accounting |
| Stale baselines | A matching-looking different raster cannot accept an update; no partial raster/plan escapes |
| Working limits/cancellation | Exact peak acceptance, one-byte copy/patch deficits, worker metadata rejection and cancellation without source mutation |
| Retained completion and destination preflight | Independent exact-plan lifetime and final release; idle/queued/READY/transferred rejection preservation, closed/exhausted guards and untracked compatibility |

The tiny fixture resolves a two-face JSON model and prepares two standard-library-generated PNG sprites through the production texture/atlas pipeline. A four-pixel page limit places animated/static slots on separate pages, making page sharing observable. One-pixel frame gutters and a half blend have direct known byte expectations. Synthetic catalog/source receipts identify a fixture, never a verified client archive or native engine result.

The context-free runner set compiles without external dependencies: 88 CPU production sources, four display math helpers and four harnesses (96 total). GPU ownership/coordinator rejection, queue lifecycle, worker races, allocation/fence/cleanup failures, native timing parity and real pixel/readback comparisons still require deferred Windows validation. Existing interpolation/filtering/mipmap guards and final editor integration remain pending.

## Queue replacement and failed-request recovery

[Atlas queue preflight](../renderer-gl/ATLAS-RECOVERY.md) now preserves earlier same-scene work when the new displayed/plan baseline is invalid. Owned submission still consumes the supplied wrapper while independent owners remain live. Exact current FAILED tokens can reopen after cleanup, with no implicit plan retention or retry loop. Follow-up compilation in the restored workspace now covers these queue changes; runtime/GPU validation remains pending.

## Owned upload creation and input release

[Direct GPU factory handoff](../renderer-gl/INPUT-HANDOFFS.md) now keeps a created atlas uploader locally owned until supplied/temporary CPU wrappers finish closing. If that release prevents return, the uploader is closed on the failure path and a distinct close error is suppressed onto the original input error. The displayed atlas and independently retained CPU plans keep their existing ownership; unfinished GPU cleanup remains device-journal-owned and charged. Full and dependency-free compilation passed. All 64 prepared groups and Windows GL/failure-injection checks remain unexecuted.
