# Bounded GPU retirement

Renderer implementation, 2026-11-07. Automatic resource cleanup now uses bounded collection instead of deleting every eligible retired object during an upload reservation or world draw. Compilation and source inspection are complete; renderer execution remains deferred.

## Work and action budgets

`GlDevice.collect(CollectionBudget)` returns a `CollectionStep` with actual work, fence polls, name slots checked, names deleted, syncs deleted, allocations released and elapsed nanoseconds. An action is one zero-timeout fence poll, one nonzero object deletion or one sync deletion. Each work unit visits one allocation and performs at most one poll, slot check, sync deletion or release; skipped live, dependent, quarantined and unsignalled allocations still cost work. Every actual slot costs a work unit, including zero handles in partially created or sparse reservations. Traversing empty object families costs at most seven constant boundaries per visit.

| Budget | Actions | Work units | Cooperative time target |
| --- | --- | --- | --- |
| Default automatic collection | 64 | 512 | 1 ms |
| Maximum accepted collection | 1,024 | 262,144 | 50 ms |

Limits must be positive and cannot exceed the maximum. Calls attempt at least one work unit when the registry is nonempty, then check elapsed time between units. Individual driver operations and CPU owner release can exceed the target; this is not a hard wall-clock guarantee. GL error checks accompany operations and are not separate action charges.

A persistent round-robin cursor shares progress across allocations. Removal adjusts the cursor without skipping the next remaining allocation. One full idle sweep stops a call early. Signalled retirement readiness is remembered, so later slices do not poll the same fence again. Transfer fences, native objects, the retirement fence and final CPU release advance separately, with no repeated deletion of successful handles.

## Retained ownership and failure

An allocation retains its complete GPU/CPU/name reservation while deletion is partial. Retained CPU objects, native scene owners and parent dependency pins remain installed until final release. Deleting some handles does not create replacement credit. A child releases its parent pin only after its entire retirement completes; the parent can then begin its own cleanup. Existing shared and device ceilings remain unchanged.

An allocation whose signalled retirement fence has been deleted but whose final CPU release is pending remains collectible. It is not treated as quarantined merely because its fence handle is zero. Missing readiness/fence proof or an explicit cleanup failure remains quarantined. Native errors preserve unfinished handles and accounting for synchronized device teardown; the collector does not silently refund failed cleanup.

`reserve`, storage expansion and the world renderer's draw preamble use the default bounded collector. A reservation can reject while earlier eligible resources remain charged beyond that slice; callers can advance explicit bounded cleanup and retry. No source, scene extent, configured height or rendering feature is reduced to fit.

The existing no-argument `collect()` remains a compatibility full pass and resumes partially deleted allocations. Explicit `close()` still synchronizes and attempts complete dependency-ordered teardown. It also resumes partial deletion, retries quarantined resources and preserves the existing failure aggregation rules. These explicit operations have no per-call action/work bound.

## Prepared verification and status

Two new GPU groups prepare invalid-budget/receipt rejection, hard action/work bounds, fair polling/deletion, exact sparse-slot coverage, cached readiness, all seven object families, transfer/retirement sync accounting and unchanged host bindings. Additional cases cover automatic reservation/expansion limits, complete accounting through partial deletion, retained CPU/source owners, dependent-first release, fence-deleted intermediate state, compatibility collection and synchronized teardown after partial cleanup.

Both compilation sets pass: 144 full Java 25/LWJGL renderer/validation files and 106 dependency-free files. All 143 prepared groups remain unexecuted: 105 CPU/context-free and 38 GPU. No renderer harness, shader, GL context, native comparison or workflow ran. Fault injection, genuine Windows/native/GPU validation, total-process memory/performance measurement, renderer completion and final editor integration remain pending. Unsupported previews remain disabled. No measured frame-time improvement or driver behavior is claimed.
