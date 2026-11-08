# Scalar, map and image admission before target storage

Renderer implementation, 2026-11-07. Scalar, native-map and original-image frames now admit their complete known footprint before creating native target storage. Scalar assembly previously created the target before the renderer attempted draw-scratch admission. A scratch-budget failure could therefore create storage and leave fenced retirement without returning an image. Compilation and source inspection are complete; renderer execution remains deferred.

## Admission and drawing ownership

All three frame families retain their exact source and reserve the existing frame journal first. Scalar frames then reserve the exact `GlNoiseCulling.workingBytes(surface, mode)` CPU footprint. Each family finally uses `GlTarget.admit` to reserve `width * height * 8` GPU bytes and all three target object slots. The frame journal pins source and target allocations before initialization. Map/image drawing retains its existing fixed bookkeeping and has no source-sized temporary payload reservation.

Scalar drawing has an internal admitted-scratch path. It verifies exact CPU size and empty GPU/name/source/dependency ownership, then uses the original drawing helper. That helper unwinds its arrays before scratch retirement. Public raw rendering still reserves its own scratch; immutable frame assembly passes its existing journal without reserving a second copy. Hierarchical, linear and disabled culling retain their original footprints and draw behavior.

| Obsolete gate | Frame state |
| --- | --- |
| First | Original source/transform checks complete; no new admission |
| Before storage | Source owner, frame journal, exact scalar scratch where applicable, and unstarted target admitted |
| After storage | Native target initialized and host state restored; drawing not started |
| After drawing | Drawing state restored and scratch released; immutable result not yet returned |

The existing four callbacks remain in that order. Known admission failure now precedes the second callback. Closing a candidate before initialization refunds unused target storage without a GPU fence. Every failure path visits the frame journal, remaining scalar scratch, target and retained source, preserving the primary error and suppressing distinct cleanup failures. Started targets follow ordinary fenced retirement. Incomplete cleanup remains device-owned and charged.

Reservations must fit alongside all live/retired ownership under unchanged shared/device limits. Automatic bounded collection may reclaim unrelated eligible retirement during admission. Metadata preflight, original sample/decoded-image identity, source completeness, target format, shader behavior and exact frame receipts are unchanged. Independently retained earlier images remain immutable. Admission covers known payload/object allowances rather than all JVM/driver overhead, and cannot guarantee driver allocation success.

Connected map/image/scalar views retain their existing explicit source/view invalidation policies. This change does not create a replacement queue or revive invalidated output. Native readiness, version-matched selected assets, configured height, generation limits and unsupported-preview gates remain unchanged.

## Prepared verification and current status

Two new GPU groups prepare scalar frames in every culling mode and map/image frames alongside independently retained earlier output. Cases cover one-byte-short scratch/target admission, complete pre-storage counters, unchanged bindings, cancellation and thrown callback errors at every gate, exact primary exception identity, immediate unused refunds, ordinary started retirement, exact-budget rendering without duplicate scratch admission, retries and original pixels/source/style receipts.

Both compilation sets pass: 144 full Java 25/LWJGL renderer/validation files and 106 dependency-free files. All 157 prepared groups remain unexecuted: 107 CPU/context-free and 50 GPU. No renderer harness, shader, GL context, native comparison or workflow ran. Renderer completion, genuine Windows/native/GPU validation, cleanup fault injection, total-process memory/performance measurement and final editor integration remain pending. Unsupported previews remain disabled.

[Map/image navigation](CANVAS-NAVIGATION-ADMISSION.md) now uses one-shot prepared frame owners, preserving the public direct-render gates and admitted footprint while retaining reservations across controller navigation and advance. All 169 prepared groups remain unexecuted; both compilation sets pass (144/106 files).
