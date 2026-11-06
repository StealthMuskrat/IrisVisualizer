# Immutable world material refresh

Renderer implementation, 2026-11-06. Compilation and source inspection only. No renderer contract, GL operation, native producer or pixel comparison has executed. Renderer-stage validation and final editor integration remain deferred; unsupported previews stay disabled.

## Separate immutable vertex streams

World uploads now store position/normal/UV as the original eight-float stream and tint/shading/final lighting in a separate material buffer. Vertex and material attributes still refer to the same original face/corner order. CPU meshes, native IDs/states/biomes, offsets, UVs, indices, ranges, translucent centroids and picking data are unchanged. The shader attribute locations and lighting arithmetic remain unchanged.

| GPU mode | Geometry stride | Material stride | Total quad bytes including indices |
| --- | --- | --- | --- |
| Simple directional lighting | 32 bytes | 4 bytes | 168 bytes |
| Supplied final RGB lighting | 32 bytes | 16 bytes | 216 bytes |

The material stream contains explicit RGB24 tint plus one raw shading/emission byte. Supplied final RGB adds three floats. Each drawable section now uses three buffers (geometry, material, indices) instead of two. Marker sections retain their existing instance buffer. Total GPU payload bytes are unchanged; object-name demand increases by one buffer per drawable section within existing limits. CPU full uploads copy bounded geometry/index slices directly into staging and pack the material words separately, avoiding per-word material branching in geometry transfer.

## Exact-source GPU copying

`GlUpload.materials(renderer, source, tint, lighting)` borrows a complete world GPU scene and prepares a new independent scene for that exact original CPU scene. Replacement tint/final lighting must pass the ordinary completeness, required-color, exact-scene and coordinator rules. A null lighting argument selects simple directional lighting; it can switch either direction without repacking geometry on the CPU. A null tint follows ordinary captured-palette fallback and required-tint validation.

The operation retains an internal GPU source wrapper and a fresh CPU source lease through destination admission. The caller may close its GPU wrapper after successful begin; that closed wrapper rejects further rendering/metadata use even while the internal copy owner keeps storage live. Internal sharing is limited to source lifetime management and does not expose interchangeable scene aliases to atlas/draw-plan callers. Fresh ordinary `GlUpload.begin` scene leases keep their consuming contract.

Geometry, indices, marker instances and unchanged base-atlas pages copy into freshly allocated GPU storage. Atlas copying uses a temporary read framebuffer, validates completeness and detaches each source page before yielding. Only replacement material words transfer from the CPU: 16 bytes per original face with simple lighting, or 64 bytes with supplied final RGB. The full source/atlas/geometry remains complete; empty scenes invent no dummy buffer/texture. An optional [displayed atlas snapshot](DISPLAYED-ATLAS-REFRESH.md) can now supply the replacement base pixels and clock. Without it, copying inherits the GPU source scene's current base, including any earlier copied snapshot.

CPU-uploaded and GPU-copied bytes together consume each step's transfer budget. Every copy/staging chunk is at most 64 KiB and also respects available step/row capacity. Storage, copy and layout phases obey existing action/work/time yielding and restore host transfer bindings on every return/error. `Progress` adds `copiedBytes` and `totalCopyBytes`; `Step` adds `copiedBytes`, with legacy constructors retained. Existing uploaded/total fields describe CPU transfer. Presenters can combine the two byte pairs and must still wait for READY before taking output.

## Ownership, admission and cleanup

The replacement reserves its full CPU source/material payload and full independent GPU output under existing byte/name/allocation ceilings. Copying adds one temporary framebuffer journal/name when atlas pages exist. Old, new and retired source/output storage overlap conservatively. No budget is raised, ownership credited twice as free memory, source geometry truncated or source texture overwritten.

After all work and host restoration, the uploader retires its temporary framebuffer, closes the internal source owner and checks cancellation again before READY. Closing a final source wrapper queues its normal retirement fence after the last copy. The finished replacement depends on the renderer, with no GPU parent-scene chain. Repeated material changes therefore do not pin all intermediate GPU scenes. Independent saved images and their original CPU inspection leases keep their own lifetimes.

Failure/cancellation attempts temporary framebuffer, destination and source cleanup, preserving the primary error and distinct suppressed cleanup errors. Failed retirement stays journaled/quarantined for device teardown. Initial admission rejection closes only temporary source owners and preserves the borrowed caller source. `materialsLit` consumes a fresh `RenderInput<WorldLighting>` through destination admission/rejection and checks the shared coordinator. Public world handle entry points now distinguish closed wrappers from internally retained storage.

## Queues and connected world view

`GlUploadQueue.materials(nextRevision, source, tint, lighting)` follows the existing [replacement-admission boundary](REPLACEMENT-ADMISSION.md), admitting the fresh copy before retiring accepted work or changing its token. `GlWorldView.materials(nextWorldRevision, tint, lighting)` requires a complete current source and a newer caller-supplied world revision. It retains the camera, viewport, clip and external animation clock, starts the material upload, and then follows ordinary source/order/frame/animation replacement. Rejected initial admission preserves the old view and tokens; later accepted-transfer errors use the ordinary failed-upload retry path.

No native generation, model baking or CPU mesh rebuild occurs. The new GPU scene preserves the exact CPU source, so a caller can rebind an existing CPU `WorldDrawOrder` with `GlDrawPlan.create` rather than recompute depths. GPU draw plans and atlas frames remain bound to their exact GPU scene; old ones cannot be passed to the new scene. The connected view currently rebuilds its normal camera order and animation coordinator after adoption. Final editor material controls remain pending.

## Prepared evidence

Two new GPU groups prepare full-upload versus copy pixel comparisons, both lighting-mode transitions, exact material/copy totals, source closure after begin, closed-wrapper rejection, repeated refresh without GPU ancestry, immutable older frames, empty/marker/cutout sources, original translucent order rebinding, stale materials, deficient shared admission, owned lighting, cancellation in framebuffer/texture/geometry/material/index phases, queue replacement preservation and connected-view adoption. All assertions remain unexecuted.

All 137 production sources, five harnesses and one fixture compile with Java 25/LWJGL (143 files). The unchanged dependency-free set contains 105 files. All 98 prepared groups remain unexecuted: 76 CPU/context-free and 22 GPU. Actual copy/pixel equivalence, retired-resource fault injection, driver scheduling, performance/total memory and genuine Windows/native comparison still require renderer/integration validation. Native readiness, selected-client requirements and unsupported-preview gates are unchanged.

[Owned queue/view lighting handoff](WORLD-CONTROL-RECOVERY.md) now adds consuming `materialsLit` entry points with tracked coordinator checks and input cleanup before replacement. The same update resolves the initial-atlas compilation gap and adds explicit animation retry. Full compilation covers all 143 files; all 100 groups remain unexecuted.

[Displayed atlas preservation](DISPLAYED-ATLAS-REFRESH.md) now carries the complete current animation frame through raw/owned queue and view refresh, with exact GPU-scene raster receipts and changed-page CPU admission. All 143 sources compile; all 102 groups remain unexecuted.
