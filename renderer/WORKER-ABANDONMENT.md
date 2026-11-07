# Worker abandonment through release failures

Renderer implementation, 2026-11-06. Compilation/source inspection only. Both full Java 25/LWJGL and dependency-free compilation pass. Renderer execution, native/GPU comparison and final editor integration remain deferred; unsupported previews stay disabled.

## Detach before releasing owned data

The remaining atlas, client-asset, image-decode, standalone visual and pointer workers now detach abandoned queue state before fallible release. Previously, release could throw while a closed READY completion or cancelled pending request still occupied its worker field. That could retain an unadmitted request graph, expose closed output to `take`, or leave retained counters reporting abandoned data.

| Workers | Ownership detached before release |
| --- | --- |
| `AtlasFrameWorker` | Pending request, READY plan, completed reservation counter |
| `ClientAssetWorker` | Pending path/settings, READY owned catalog input, completed byte counter |
| `ImageDecodeWorker` | Pending encoded source, READY decoded image/hold |
| `WorldVisualWorker` | Pending frozen choices/assets, READY visual result/hold |
| `WorldPickWorker`, `NoisePickWorker` | Pending borrowed inspection request and detached completion slot |

Producer jobs already running are marked cancelled and keep their existing ownership/admission through normal stack unwind. Idle pending/READY detachment does not release an active job's reservation early. Pointer completions remain detached values with no source owner; they are cleared before closing pending admission.

The four preparation workers attempt pending and completed cleanup even if the first release fails. Exact primary RuntimeException/Error identity is preserved, with distinct later errors suppressed once per cleanup attempt. Completed local counters reset before release. Every one of these six submission paths also closes a newly admitted, uninstalled job hold if abandoning prior work throws, matching the existing map/scalar worker rollback pattern.

## Tokens and error handling

Initial admission rejection continues to preserve prior accepted work. Once abandonment has started, its detached output is no longer adoptable even if release then fails. The failure propagates; a cleanup failure does not commit the new invalidation/replacement revision. Previous status/revision metadata may therefore remain visible, while `take` returns empty for the abandoned completion. A caller must handle the thrown cleanup error rather than infer deliverable ownership from the old phase alone.

After successful cleanup or an error occurring after actual release, the next ordinary submission can use the next unspent revision. Failed shutdown still attempts executor shutdown and CLOSED metadata; another `close` retries remaining shutdown work while input gates stay closed. Coordinator failures retain their own conservative accounting semantics. These changes do not claim that every arbitrary failed release has freed memory or that active/background races have been executed.

## Prepared release-failure matrix

One new world CPU group prepares six worker families. The four preparation families cover pending and READY output, invalidation and shutdown, with RuntimeException, AssertionError and OutOfMemoryError injected after account removal. Both pointer families cover admitted pending requests under the same invalidation/shutdown/error matrix. Checks require exact error identity, one attempted account removal, detached fields/counters, no closed output delivery, same-worker revision progression/retry, same-coordinator reuse, closed input gates and independently owned source/index survival.

The fault hook changes only the coordinator's account-removal behavior; worker jobs, admission handles and phase metadata are not rewritten. Manual executors control when a real preparation runs. Asset READY cases use one privately created tiny ZIP containing the pinned version ID and a small synthetic model, deleted by the test owner. They never open user-selected client archives and do not establish native/client fidelity. Other data comes from the existing supplied in-memory PNG/model/world/scalar fixtures. No such ZIP has been created by compilation.

All 137 production sources, five harnesses and one fixture compile with Java 25/LWJGL (143 files). The 105-file dependency-free set also compiles. All 103 prepared groups remain unexecuted: 77 CPU/context-free and 26 GPU. Active cleanup races, broader source failures, original-client/native output, real GPU behavior, performance/total application memory and final editor adoption remain pending.
