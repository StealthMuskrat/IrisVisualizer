# Initial world atlas clock adoption

Renderer implementation, 2026-11-06. The previously unavailable environment has returned, and Java 25/LWJGL compilation now passes for the full 143-file renderer/validation set, including this initial-atlas change. No renderer contract or GPU operation has run. Renderer-stage runtime/GPU/native validation and final editor integration remain deferred; unsupported previews stay disabled.

## First-image correctness

A newly uploaded world scene begins with its original base atlas. When the host has already requested another animation clock, the CPU atlas plan and incremental GPU update can finish after scene upload. Previously, the connected view could publish a first image using base pixels before applying the requested clock. Material refresh recreated the same opportunity because its independent atlas copy starts from the base scene.

`GlWorldView` now tracks whether the current source has prepared its initial requested atlas pixels. After source adoption it waits for `GlWorldAnimation.settled()` before sorting/drawing the first image. The predicate requires the clock metadata to have been observed, no remaining CPU request, and CURRENT animation status. It accepts canonical unchanged pixels as well as completed pixel updates. A requested tick need not equal the base raster receipt when their canonical pixel samples are identical; receipts continue to describe the actual raster used.

The existing rule that full-scene and animated-atlas uploads share one caller budget is unchanged. No animation upload runs in a step that already advanced scene upload. Initial presentation can therefore take additional advances while metadata, worker output and GPU pages catch up. It publishes a complete matching image instead of a temporary base-clock image. No new payload reservation, larger limit, native sampling or model/mesh rebuilding is introduced.

Source release resets the initial-atlas flag for full replacement, material refresh, invalidation, retry and shutdown. Existing caller-owned images remain immutable and can remain visible while the host waits. Once a source has been presented, normal animation and camera/clip navigation can continue using its last complete atlas during later asynchronous updates. This avoids making every animated camera move wait for a new clock sample.

## Failure visibility

An animation coordinator that reports FAILED without throwing now makes the world view FAILED with its code/reason. Detailed model/atlas problems remain available through `animationStatus()`. A failed initial atlas never produces FRAME_READY for base pixels. Ordinary source/worker/GL exceptions keep their existing propagation and cleanup.

A complete source remains available for the existing explicit view retry route. The [explicit animation retry](WORLD-CONTROL-RECOVERY.md) now re-arms the current failed clock with `retryAnimation(expectedWorldRevision)`; the host may choose another clock first with `clock`. The earlier `clock` plus `redraw` route remains available. If admission remains insufficient, it rejects again under the same configured limits. Source-upload failures keep their separate `retryUpload` path.

## Prepared regression coverage

One added GPU group prepares a nonzero first clock for scheduled and interpolated textures, material refresh at that clock, replacement at an unchanged scheduled sample or interpolated midpoint, saved-image immutability, and a zero animation-worker allowance. Required initial-plan rejection must preserve its diagnostic and expose no incorrect first image. Earlier no-clock/default-clock flows retain their source paths.

There are now 100 prepared, unexecuted groups: 76 CPU/context-free and 24 GPU. The production/harness file counts remain 137 production, five harnesses and one fixture (143 total); the dependency-free set remains 105 files. The compilation gap is resolved; renderer-stage execution remains deferred until implementation is complete. No pixel result, native parity or GPU readiness is claimed.

[Displayed atlas preservation](DISPLAYED-ATLAS-REFRESH.md) now allows material refresh to start from its complete displayed pixels. A matching requested clock therefore needs no animation reconstruction; changed clocks still use this initial readiness guard. Both raw and owned connected paths are prepared, with all 102 current groups unexecuted.
