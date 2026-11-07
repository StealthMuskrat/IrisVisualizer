# World-frame admission before replacement

Renderer implementation, 2026-11-07. Completed world-frame queues now reserve a replacement's entire known footprint before accepting its revision or releasing an earlier READY image. Metadata-valid replacements previously could discard that image and then fail target or draw-scratch admission. Compilation and source inspection are complete; runtime/native/GPU validation remains deferred.

## Prepared ownership and overlap

Internal `GlFrame.prepare` repeats source/order/atlas/camera/target preflight, retains the exact CPU scene and admits three independent journals:

| Journal | Known footprint | Ownership |
| --- | --- | --- |
| Frame | Displayed CPU atlas pages distinct from the scene's base raster | CPU scene lease, exact raster and scene dependency |
| Draw scratch | Exact `GlWorldCulling.workingBytes(scene, mode)` | Existing bounded math, offsets, masks and prefixes |
| Target | `width * height * 8` GPU bytes and three object slots | RGBA8 texture, depth24 renderbuffer and framebuffer |

The target is admitted last. No preparation step creates target native handles/storage, invokes the obsolete supplier or releases earlier queue output. Reservations must fit alongside all current live/retired ownership under unchanged device/shared limits. There is no replacement credit. Existing bounded automatic collection may reclaim unrelated eligible retirement during admission.

Setup failure visits every newly owned journal and lease. Ordinary unused cleanup refunds the full candidate allowance without a target fence. The primary error is preserved with distinct cleanup errors suppressed. Incomplete cleanup remains reachable through device journals/quarantine and retains its charges.

## Target lifecycle and admitted drawing

Internal `GlTarget.admit` reserves full storage with zero handles and an incomplete wrapper. `initialize` uses the existing device-owned one-way storage transition before native operations. Target completeness is published after clear/storage checks and host-state restoration succeed. A failed initialization cannot be restarted or refunded as unused merely because no handle was returned.

Unused independent targets refund through `discardUnstarted`. Foreign, dependent or started storage cannot use that path. A pinned unused target follows ordinary retirement; repeated close remains idempotent after its dependent releases. Started targets keep fenced cleanup. Public `GlTarget.create` still returns the same initialized target through these two phases, retaining formats, attachments, hardware bounds and context requirements.

Prepared rendering validates liveness again, checks obsolescence before storage, initializes the target, checks after storage, then draws using its already admitted exact scratch journal. The renderer verifies the scratch's CPU size and empty GPU/name/source/dependency ownership. Its array-owning draw helper unwinds before scratch retirement. Successful transfer moves frame/target ownership to the completed image; preparation closure drops all borrowed scene/atlas/order references and releases untransferred resources. Preparations render at most once and cannot render after close; unfinished cleanup can be retried by closing again.

## Queue acceptance and callbacks

The queue builds its next status before admission. An initial admission failure preserves the exact old status object, revision and queue-owned READY frame, or the original IDLE/WAITING state. The same unspent token can be submitted after pressure is relieved.

After preparation succeeds, the queue publishes its accepted revision/RENDERING status, activates the reentrancy guard and releases prior queue ownership. Acceptance uses preconstructed metadata and primitive assignments; it does not call a second allocating invalidation path. Render, invalidate, take and close remain rejected during obsolete callbacks.

All five queued callback gates remain: immediately after acceptance/old-owner release; before target storage; after storage; after drawing; and before final result adoption. Direct frames retain their original initial callback before admission, followed by the three prepared-render gates. Accepted cancellation/failure keeps the existing CANCELLED/FAILED protocol and does not restore superseded queue output. Independently retained earlier frames remain immutable.

Explicit upstream invalidation still abandons obsolete output immediately. A rejected candidate then preserves that new WAITING state. The host may deliberately release earlier ownership before retrying when conservative overlap cannot fit. Admission remains synchronous on the original context, covers known payload/object allowances rather than all JVM/driver overhead, and cannot guarantee driver allocation success or establish preview readiness.

## Prepared verification and current status

Three new GPU groups prepare one-byte-short frame-atlas, scratch and target rejection; READY/WAITING preservation; zero callbacks before acceptance; exact peak success; old/new animated pixels and clock receipts; unused target/preparation refunds; all culling strategies; one-shot rendering/initialization; foreign/pinned/started lifecycle rejection; and successful ownership transfer.

Device-local cases prepare GPU-byte, journal-slot and object-slot deficits, delivery of earlier pixels, same-token retry after explicit prior-owner release, reentrant callback rejection, all five cancellation gates and later recovery. Fault injection and actual driver behavior remain deferred.

Both compilation sets pass: 144 full Java 25/LWJGL renderer/validation files and 106 dependency-free files. All 155 prepared groups remain unexecuted: 107 CPU/context-free and 48 GPU. No renderer harness, shader, GL context, native comparison or workflow ran. Renderer completion, genuine Windows/native/GPU validation, total-process memory/performance measurement and final editor integration remain pending. Unsupported previews remain disabled.
