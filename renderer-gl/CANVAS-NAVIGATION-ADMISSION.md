# Admitted map and image navigation

`GlMapView` and `GlImageView` now reserve the complete replacement frame before accepting navigation on a complete uploaded source. Previously, pan/zoom/fit/resize/redraw advanced the view token and released queued output; the later `advance` call could then reject the known frame footprint. A capacity rejection now preserves the exact prior navigation, status and pending or READY output.

## Prepared canvas frames

The internal `GlMapFrame.Prepared` and `GlImageFrame.Prepared` owners separate capacity admission from native initialization. Each preparation validates the original source/device/transform/viewport, independently retains the GPU source wrapper, reserves a zero-payload frame journal and admits the complete target. The journal pins the original source allocation and the target reservation.

| Reservation | Added footprint |
| --- | --- |
| Frame journal | One journal; no duplicate source payload |
| Target journal | One journal; `width * height * 8` GPU bytes |
| Target object slots | One RGBA texture, one framebuffer and one depth renderbuffer |
| Source wrapper | Independent reference to the exact original uploaded data |

Admission creates no target handles or native storage. It must fit alongside live and retired ownership, including the previous queued frame or undrawn preparation. There is no credit for resources a successful replacement might release. Bounded automatic collection may reclaim unrelated eligible retirement. Existing shared/device limits and JVM/driver accounting exclusions remain unchanged.

Rendering consumes a preparation once. It validates the retained source and borrowed renderer, checks cancellation, initializes its admitted target, draws the original pixels, and publishes the exact source/view receipt only after successful drawing and host-state restoration. Frame/target/source ownership transfers to the completed frame. Closing an unused preparation refunds its target without a fence; started storage follows ordinary fenced retirement. Cleanup visits each owner and preserves primary failures. Unused wrappers can also finish closing after synchronized device teardown.

The existing public direct-render methods use the same preparation path and preserve their source ownership, callback order, target footprint and public signatures. A caller can close its original source wrapper after preparation; the independent retained wrapper remains valid. Preparations cannot be drawn again after use or closure.

## Navigation ownership

For a complete current source, a changed `view`, pan, zoom, fit, resize or explicit redraw first prepares its new frame. If admission rejects, the controller leaves the original transform, viewport, view revision, status object, worker/upload state and queued output intact. An unchanged view remains an allocation-free no-op; redraw deliberately requests another frame.

After admission succeeds, the controller accepts the next view revision, releases its prior queued frame/preparation and retains the new preparation until `advance`. That draw reuses the held reservations, so later shared-capacity pressure cannot introduce a second known-frame admission failure. A fresh navigation can supersede an undrawn preparation only after its own admission succeeds. Old unused targets refund immediately; failed replacements preserve the earlier undrawn request.

Map viewport configuration while source preparation is pending retains its earlier policy and admits a frame once its upload is complete. Explicit source invalidation, accepted source replacement and controller closure abandon pending frame preparations. Caller-transferred older frames remain independently owned and immutable. Histogram/source identity, original image dimensions, native samples and colorization/decode receipts remain unchanged.

The affected controllers are the 2D native-map and original-image views. [Scalar navigation now has its own complete admission path](SCALAR-NAVIGATION-ADMISSION.md); [world navigation now admits late-bound frame/order storage](WORLD-NAVIGATION-ADMISSION.md). Driver, host-state restoration and Java metadata allocation can still fail after capacity admission; those failures retain the existing display-failure and explicit-redraw behavior. No native sampling, CPU colorization, decoding or source upload is scheduled by navigation.

## Prepared validation and current scope

Three GPU groups prepare map/image frame admission counters, unused closure, cancellation at each prepared draw gate, single-use ownership, source-wrapper closure, post-device-teardown cleanup and exact original receipts/pixels. Connected pipeline cases prepare rejected READY and undrawn navigation, unchanged-view no-ops, exact shared-budget admission, drawing with zero additional allowance, complete panned-pixel and clear-margin expectations, original-image immutability, supersession, invalidation and closure before drawing.

Independent GPU-byte, journal and object-slot limits prepare both READY and undrawn rejection. The earlier frame must remain deliverable, partial candidate admission must refund, and the same unspent view revision must succeed after earlier output releases. The shared test helper still uses the ordinary CPU worker and GPU upload paths for both controllers.

All 144 full renderer/validation sources and the 106-file dependency-free set compile with Java 25 (LWJGL 3.4.1 for the full set). There are 169 prepared, unexecuted groups: 109 CPU/context-free and 60 GPU. No renderer test main, context, shader, native operation, Windows validation task or workflow ran. Genuine native/API compatibility, GPU correctness/fault injection, total-process memory/performance measurement, renderer completion and final editor integration remain pending. Unsupported previews stay disabled.
