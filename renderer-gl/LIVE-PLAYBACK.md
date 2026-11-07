# Bounded live atlas playback

Renderer implementation, 2026-11-06. Java 25/LWJGL compilation passes for the full 143-file source/validation set and the 105-file dependency-free set. Two additional GPU groups bring the prepared total to 105: 77 CPU/context-free and 28 GPU. All remain unexecuted. Native/GPU correctness and final editor integration are pending; unsupported previews stay disabled.

## Host clock contract

`GlWorldView.playbackClock(tick, partialTick)` and the standalone `GlWorldAnimation.playbackClock` accept a continuously advancing host clock. They use the same original-context, nonnegative-tick and finite `[0,1)` partial-tick checks as `clock`. There is no internal timer or native generation work.

An admitted CPU plan or partial GPU update finishes before the next live clock is sampled. Incoming ticks replace only the latest tick/partial-tick fields, so there is no growing clock history, per-tick atlas preflight, extra pending plan or extra worker. At adoption, the coordinator keeps its immutable complete frame and observes the latest desired clock on the next advance. Frames that the host skipped are never replayed. Pausing the clock allows the next complete update to catch up to its canonical pixels.

This addresses cancellation starvation when the host advances time more frequently than CPU planning and bounded GPU upload can complete. It guarantees progress of an admitted sample when the worker, context and budgets can make progress; it does not impose a frame-rate or wall-clock latency guarantee. The renderer may display an older complete sample while the desired clock moves ahead.

| Entry point | In-flight work | Initial presentation |
| --- | --- | --- |
| `clock(tick, partialTick)` | Immediately observes the requested seek on the next advance; replaces different pending pixels or cancels back to displayed pixels | Waits for the requested canonical pixels |
| `playbackClock(tick, partialTick)` | Completes its admitted sample, then samples only the latest desired clock | Waits for one complete admitted sample, or confirmed unchanged pixels |

Either method selects its scheduling mode. Calling `clock` after live playback selects seek behavior even when both calls supply the same numeric clock. A live backward jump also coalesces; use `clock` for an immediate forward or backward seek. Equal canonical samples still avoid pixel updates. Partial ticks retain the existing tick-quantized interpolation behavior.

## First frame and receipts

The connected view uses `presentable()` for its initial-atlas guard. Explicit seeks still require `settled()`, meaning the latest clock was observed and no preparation remains. Live playback may present after one admitted sample completes even if a newer live clock is waiting. Before that completion, unrelated base-clock pixels cannot prime the view. Continuously changing clocks therefore cannot prevent initial presentation solely by perpetually moving the target.

`settled()` continues to mean that the latest canonical pixels have caught up. Playback does not relabel it or rewrite the displayed raster's tick. Status keeps the latest requested clock and the actual displayed clock separately. Completed frame and readback receipts identify the raster used to render those pixels, and earlier caller-owned images remain immutable.

Once the source is primed, camera/clip changes may continue drawing its last complete atlas during preparation. Complete atlas adoption requests a redraw and advances the view token without rebuilding geometry or invalidating camera-dependent face order. Full-scene and atlas upload still share one caller step budget.

## Replacement, failure and ownership

The view retains its selected scheduling mode and latest desired clock across source upload, material refresh and world replacement. Material refresh starts from the current displayed atlas; it can avoid animation work entirely when those pixels match the desired clock. A full replacement starts its own initial-sample guard and cannot adopt a plan for the previous exact raster/scene.

CPU preparation or GPU/cleanup failure retains the existing FAILED contract. Neither mode permits partial adoption or silently substitutes base pixels after required preparation fails. A repeated failed clock re-arms standalone preparation. `retryAnimation(expectedWorldRevision)` preserves the connected view's selected mode and latest requested clock, using the existing complete source and unchanged admission ceilings. Insufficient memory fails again; choosing already displayed pixels can recover without a new worker payload. Old transferred images stay caller-owned.

Existing worker, CPU coordinator, GPU journal, atlas ancestry and fence-retirement accounting remain in force. Playback introduces scalar scheduling flags only, with no new payload allowance, worker thread, native data sampling, geometry rebuilding or readiness override. Explicit seek/world cancellation and nonblocking shutdown retain active-work charges until their stacks unwind.

## Prepared deferred coverage

The two new GPU groups prepare scheduled and interpolated fixtures with tiny upload steps: continuously changing clocks with a stable admitted revision; first-sample GPU pixels and truthful partial receipts; catch-up after pause; explicit seek cancellation during GPU work; switching modes at the same desired clock; host-state restoration; immutable saved frames; borrowed-worker shutdown; first connected presentation under continuous ticks; navigation during preparation; material and world replacement; and zero-budget failure, retry and recovery without source reupload.

These are source-prepared assertions, not execution evidence. Actual worker scheduling, GPU pixels, driver behavior, native parity and final UI adoption still require the deferred renderer/integration stage.
