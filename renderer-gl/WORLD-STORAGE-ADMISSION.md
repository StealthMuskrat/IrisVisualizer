# Complete world upload admission

Implemented 2026-11-08. World uploads and material copies now reserve their complete known CPU/GPU/object footprint before the upload queue accepts a replacement revision. Previously, initial admission retained CPU inputs, then `RESERVING` expanded GPU admission after incremental validation. A known GPU/name deficit could therefore arrive after the queue had released its earlier accepted work. The new order preserves that work when initial capacity admission rejects.

## Metadata-based footprint

Verified `WorldScenePlan.Usage` supplies face-section and marker-section counts without rescanning the source. The existing atlas-page walk also checks hardware dimensions and bounded row staging before admission. All budgets and source/native/asset readiness requirements remain unchanged.

| Resource | Complete destination admission |
| --- | --- |
| CPU inputs | Original scene known payload, resolved tint, final RGB lighting, and separately retained changed displayed-atlas pages |
| GPU geometry | Original packed vertices and indices: `faces * MeshPacket.PACKED_BYTES_PER_QUAD` |
| GPU materials | 16 bytes per face for tint/shading; another 48 when supplied final lighting is present |
| GPU markers | 12 bytes per original diagnostic cell |
| GPU atlas | Complete destination RGBA pages |
| Scene object slots | Three buffers and one VAO per face section, one buffer and one VAO per marker section, and one texture per atlas page |
| Copy framebuffer | One additional journal and framebuffer slot for material copies with atlas pages; zero payload bytes |

The destination journal retains the exact CPU scene lease and renderer dependency. Material refresh keeps its independent scene/optional displayed-atlas owners. Initial setup admits the destination and optional copy framebuffer together before returning an upload. If either reservation or subsequent setup fails, cleanup visits every newly admitted journal, releases temporary ownership and suppresses distinct cleanup failures on the original error. Already device-owned CPU leases still reject intact. Owned lighting remains charged through destination admission and is consumed on rejection under the existing ownership contract.

There is no credit for resources a successful replacement might later release. Live and retired resources remain charged. Bounded automatic retirement may collect unrelated eligible resources during admission. Empty scenes with no textures or geometry retain only their CPU journal and create no dummy GPU storage.

## Incremental storage and lifecycle

Validation remains incremental and checks actual mesh/raster, face, tint, transparency and marker counts. The public `RESERVING` phase remains for compatibility; it now verifies the complete admitted arrays/bytes against those counts instead of expanding capacity. Native name creation and actual allocation still happen in the existing budgeted storage phases. Transfer/copy/storage receipts, complete source coverage and READY publication remain unchanged.

`reserveStorage` now supports an original CPU scene lease and zero-byte object storage, allowing the copy framebuffer to be admitted before native creation. Only explicitly never-started storage on its original device, without dependents/fences/handles, may refund immediately. Its CPU lease and parent dependency are released by the same journal cleanup. A CPU scene lease is not a GPU command and does not invalidate that proof. Ordinary journals and started zero-handle storage retain their previous discard rejection.

Destination and copy framebuffer transition independently before their first native creation. Closing/cancelling during validation refunds the complete unused destination and framebuffer without a fence. If only the copy framebuffer has started, its journal retires normally while the unused destination refunds immediately. Once destination storage starts, all its reserved bytes/names and CPU ownership remain charged through ordinary fenced retirement. Driver allocation or later validation failures can still occur after a queue has accepted a replacement; initial capacity admission is not a guarantee of successful GPU execution.

`GlUploadQueue` and `GlWorldView` already install replacements only after `begin`/`materials` succeeds, so they inherit complete initial admission. IDLE/WAITING, unstarted, started and READY queue states preserve exact accepted status, revision, progress and ownership on capacity rejection. The same unspent replacement revision can be retried after pressure is relieved. Explicit upstream invalidation keeps its existing meaning.

## Prepared verification

Three GPU groups prepare full and material-copy admission for solid/tinted/lit, marker/mesh and empty scenes; exact shared-budget boundaries; owned input rejection; initial storage counters; bounded original traffic; differential pixels and unchanged earlier images. Cancellation cases cover validation, copy-framebuffer-only storage, started destination storage and complete refunds after collection.

Queue cases prepare rejection/recovery in five states, original output delivery and connected world-view READY preservation. Device cases lower GPU-byte, journal and object-slot limits independently, including a material-copy destination that fits while its copy framebuffer does not. Cleanup must preserve the old queue, counters and renderer pins. Lifecycle cases retain foreign-device and started-zero-handle rejection while proving original CPU-lease refunds for unused object-only storage. Existing displayed-atlas recovery now rejects against its complete CPU/GPU peak, and the owned-lighting receipt includes initial GPU admission.

All 144 full renderer/validation sources and the 106-file dependency-free set compile with Java 25 (LWJGL 3.4.1 for the full set). There are 163 prepared, unexecuted groups: 109 CPU/context-free and 54 GPU. No renderer test main, context, shader, native operation, Windows validation task or workflow ran. Genuine native/API compatibility, GPU pixels/state/fault injection, total-process memory/performance measurement, renderer completion and final editor integration remain pending. Unsupported previews stay disabled.
