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

## Prepared animation suite

`AnimationCpuContracts` adds 11 groups to the three earlier harnesses, for 55 prepared groups total. Its sources compile; no assertion has been executed or claimed to pass. The default Windows validation runner compiles only and now includes four suite classes. At renderer-stage validation, explicit `-Execute` launches each suite in its own JVM and stops on failure. The runner remains inspected only on this host.

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
| Retained completion | Completion/input/coordinator closure preserves independent exact-plan lifetime and final charge/account release |

The tiny fixture resolves a two-face JSON model and prepares two standard-library-generated PNG sprites through the production texture/atlas pipeline. A four-pixel page limit places animated/static slots on separate pages, making page sharing observable. One-pixel frame gutters and a half blend have direct known byte expectations. Synthetic catalog/source receipts identify a fixture, never a verified client archive or native engine result.

The context-free runner set compiles without external dependencies: 88 CPU production sources, four display math helpers and four harnesses (96 total). GPU ownership/coordinator rejection, queue lifecycle, worker races, allocation/fence/cleanup failures, native timing parity and real pixel/readback comparisons still require deferred Windows validation. Existing interpolation/filtering/mipmap guards and final editor integration remain pending.
