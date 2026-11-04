# Vanilla pure-fluid surfaces

Renderer implementation, 2026-11-03. All 118 CPU/OpenGL main Java sources compile with Java 25 and LWJGL 3.4.1. Sixteen native adapters pass syntax parsing only; genuine pinned Windows native API compilation remains pending. No renderer tests, native sampling, GL contexts, pixel comparisons or workflows ran. Unsupported previews remain disabled.

## Capture and ownership

`NativeFluidInputs.capture` supplies vanilla water/flowing-water and lava/flowing-lava geometry for actual native invisible hosts with no block entity and zero captured model offset. It reads actual native fluid height, flow, same-fluid relationships, face occlusion shapes, backward top-face visibility, biome water RGB and host light emission. It neither ticks fluids nor changes generation. Original block IDs, full states, biomes, positions and configured height remain in the snapshot and inspection path.

The admitted route is `NativeCapture.weighted` → `NativeCapture.fluids` → the existing `WorldRenderWorker` and `GlWorldView`. The weighted request still requires the caller's verified, dedicated native random source. `fluids` consumes its owned ready-choice request on success, failure or cancellation after argument admission, retains it through destination admission and requires the same memory coordinator for tracked input. Its result is fully detached. This route does not accept the deterministic `ModelRequest` directly. The generic native visual-edit API remains available.

A borrowed optional neighbor snapshot must belong to the same native source/session epoch and retain the same full vertical interval. Target cells win overlapping lookups. Both palettes are checked against the native registry identities. Native queries use these exact captured states; missing neighbors, unsupported external neighbors or unavailable block entities produce explicit original-state visual problems. No air, clamped boundary, replacement biome or inferred native state is supplied. A surrounding full-height capture can provide horizontal neighbors; uncaptured vertical boundaries remain guarded. Session serialization and source ownership remain the host's responsibility throughout capture.

Already explicit native choices and empty choices are preserved. Waterlogged/model hosts, special bases, block entities and nonzero model offsets retain their prior guarded visual. Rendering the fluid alone would discard their required base geometry, so those compositions need a separate producer.

## Geometry and materials

`FluidGeometry.Cell` contains all detached inputs to a fluid prototype: four raw corner heights, visible/overlay face masks, reverse-top flag, flow UV sine/cosine, kind, RGB and emission. Corner averaging, source winding, top/bottom/side insets, rotating top UVs, side UVs and supplied reverse faces follow the reviewed vanilla profile. Horizontal face order is north, east, south, west. Native-selected visible faces have no second block cullface declaration. Every prototype has at most eleven quads.

Shared prototypes require the exact original state index and complete cell recipe within the same source snapshot. Geometry intake retains native provenance and validates every vertex, UV, normal, winding and packet bound. Quad objects are created lazily after count admission. Fully hidden fluid cells become explicit empty choices without requiring sprite assets. Visible fluids require all sprites of their kind in the selected client archive: water still/flow/overlay or lava still/flow. Missing assets remain reported placeholders.

The new material RGB defaults to white for existing block models. GPU upload multiplies it with an existing block tint if present, using bounded RGB24 channel arithmetic. Fluid faces carry the actual radius-zero native water RGB as material color and tint index -1; lava uses white. No artificial block-model tint layer is reserved. Existing 36/48-byte GPU vertex layouts, alpha candidates, atlas animation and picking retain their existing paths.

Simple display lighting uses geometric normals, cardinal shade overrides and the captured emission floor. Explicit final scene RGB remains authoritative through the existing lighting path. Native lightmap/AO, native fluid pass selection across all sprites, UV filtering/shrink behavior and exact pixel parity are not established by source review. Neighbor-biome color blending is not implemented; the policy explicitly records radius zero.

## Bounds and validation

The factory permits 64 Mi native neighbor queries by default, lowerable up to a hard 256 Mi. Existing edit limits bound cells/changes to 16,777,216, new packets to 1,024, added packed geometry to 64 MiB and primitive working allowance to 512 MiB. The effective allowance also respects the original request/visual limits and visibility bytes. Optional neighbor index payload is charged before capture; original input, scratch/final assignment copies (eight additional bytes per cell) and new packed prototypes must fit together. Geometry and edit admission independently validate output. Palette/reference/object/native overhead still requires combined host admission.

[Source review receipts](fluid-source-review.json) link the official pinned NeoForge patch and the reviewed unofficial vanilla source mirrors. Mirror text hashes identify what was reviewed; they do not authenticate the selected archive, native API compatibility or runtime output. No Minecraft source bodies or texture assets are bundled.

At renderer completion, compile against the genuine pinned Windows dependencies, then compare actual native flat/sloped/falling/covered water and lava, all side directions, reverse faces, overlays, custom biome colors and emission. Include missing neighbors/assets, epoch and palette mismatches, exact-input sharing, cancellation, zero/count/byte/query limits, ownership failures and original-state picking. Compare animated transparency, flow UVs and pixels on a real driver. Waterlogged/special compositions, final integration and preview readiness remain pending.
