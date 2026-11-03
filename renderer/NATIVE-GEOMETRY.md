# Native visual quads and ordered composition

Renderer implementation, 2026-11-03. All 78 main Java sources compile with Java 25 and pinned LWJGL 3.4.1: 57 CPU and 21 OpenGL. No native geometry, texture/atlas/world/picking, GPU pixel, allocation, cancellation or interactive Windows tests have run. Tests remain deferred until renderer implementation is complete; unsupported previews remain disabled.

## A visual input path beyond JSON elements

`NativeGeometry.capture` accepts supplied vanilla native quads for fluid, special-renderer or other block visuals that cannot be expressed as resolved JSON element boxes. It copies them into the same `BlockMesh`/`MeshPacket` representation consumed by texture preparation, atlas binding, world sections, material passes and picking. This batch adds the renderer intake/composition path. It does not yet implement a native fluid/special-renderer exporter, run client classes, compute fluid heights/flow, choose renderers, implement block entities, infer native neighbor shapes or certify native visual parity.

The caller supplies a complete `WorldSnapshot`, source cell index, visual kind, bounded producer-policy label and immutable quad descriptions. The state is read from that original snapshot cell, preserving its exact native ID/text, vanilla block ID/properties and domains. An invalid/failed snapshot or out-of-range cell is an API error; native geometry cannot supply a replacement state. External block IDs remain explicit unsupported visuals.

Each quad has four block-local vertices in **blocks**, sprite-local UV in `[0,1]`, a supplied near-unit normal, native cardinal face label, optional native cull direction, material sprite/force-translucent flag, tint index, emission, AO and shade-direction metadata. Positions/UVs/normals are preserved, not normalized into a different shape. Input vertex records and their lists are immutable; freeze the outer quad list throughout capture. Normals must already be unit length within 0.001. Both triangles use the existing `0,1,2 / 0,2,3` winding; nondegenerate triangles must face the supplied normal. Degenerate triangles are retained. Nonplanar quads are permitted when both triangle orientations agree.

The producer must provide sprite-local UVs from its known native sprite mapping. The renderer does not rescale a face's observed UV range or guess a native atlas rectangle. Per-vertex normals/alpha, triangle-only layouts and geometry needing another triangulation are not represented by this interface; a producer must report those unsupported requirements instead of dropping attributes. A double-sided visual must include its supplied back-facing quads explicitly. Unsupported local coordinates, UVs, normals or winding yield a whole visual placeholder rather than reordered faces or approximated geometry.

Every referenced sprite must exist in the version-matched user-selected vanilla client archive. The existing decoder then checks PNG/metadata, alpha and animation limits. No asset/runtime is downloaded, no generated texture is invented, and no missing sprite is replaced. Unsupported dynamic textures still produce visual problems while preserving native IDs/states.

## Exact capture and position provenance

Every `WorldSnapshot` now exposes a fresh immutable capture UUID in addition to its caller-supplied generation receipt. `NativeMeshSource` records that UUID, generation receipt, native state ID, optional native position, visual kind and producer label. It contains no `WorldSnapshot`, mesh or client/native object reference, so picking receipts can retain provenance without retaining the old voxel arrays.

Capture binds geometry to its source position by default. `WorldModels.capture` verifies the exact snapshot UUID, complete generation receipt and native state ID before accepting an atlas-bound native prototype. During cell assignment it also verifies the bound position. Re-capturing different arrays with an equal generation receipt does not permit reuse of native geometry from the earlier snapshot.

The explicit `positionDependent=false` overload permits sharing a native prototype among same-state cells of that exact snapshot. It is the producer's assertion that geometry is position independent; it is not inferred from equal block IDs, apparent shape or neighboring states. The source label is descriptive provenance, not independent proof that a native mesher or random-choice policy ran correctly.

Native-only meshes have no JSON dispatch selections. Their quads and packed faces use `selection=-1` with a present `nativeSource`; normal JSON quads retain nonnegative indices and an absent native source. Compatibility constructors keep the previous JSON call signatures. No ticket zero, fabricated weight choice or fake model selection is introduced.

For native faces, the existing source locator uses the original block owner (`BLOCKSTATE`, original block ID, `/native/quads/N`). This is a diagnostic native-quad locator, **not** a JSON pointer into a claimed model file. Consumers inspect `nativeSource()` before presenting it as native provenance or trying to open a JSON source location. Original JSON faces retain their actual model source pointers.

## Ordered complete-cell composition

`NativeGeometry.combine(base, nativeLayer, cancelled)` joins two successful visuals of the exact same native state and captured asset archive. The added layer requires native provenance. A typical input is a JSON waterlogged block mesh followed by supplied fluid quads. The renderer neither simulates waterlogging nor changes the generated block into water; the producer supplies the complete required fluid layer for that native state.

The result keeps base quads followed by added quads. Positions, vertex order, normals, UVs, materials, tint/emission/AO/cull metadata and per-face native provenance remain intact. Original JSON selections/tickets remain unchanged; added JSON selection references are offset to their position in the combined selection list. Packed indices and aggregate bounds are rebuilt for the resulting face order. Native faces retain `selection=-1`.

Composing native layers requires the same capture UUID, generation and native state. Two position-bound layers must agree on the cell. If either layer is position dependent, the result remains bound. The mesh-wide `COMPOSITE` receipt describes ordered renderer composition; every native face retains its original producer/kind/policy. The result does not retain either input mesh, old packet arrays or a recursive composition history, although immutable vertex/metadata records may be shared.

Counts and source-plus-prospective-output array bytes are admitted before allocating combined metadata/packed arrays. Selection metadata has a separate bound, including zero-quad inputs, so repeated composition cannot grow an unbounded selection list. A failed layer with matching identity produces a whole placeholder; the first failure is returned without drawing a misleading partial block. Native state/asset mismatches fail explicitly and preserve the base state's identity.

## Existing texture, world and inspection stages

Prepare a captured/composed mesh with `TextureMaterials.prepare`, include successful preparations in `AtlasBuilder.plan`, and bind them with `AtlasMeshes.bind`. `WorldModels.Entry.from` then accepts the prototype against the exact capture/position checks. Existing palette, atlas-input, aggregate face, world-index, cache, upload and GPU ceilings all still apply. In particular, atlas planning has its existing 1,024-preparation ceiling; a producer must explicitly share truly position-independent geometry or report excess unique visuals. No limit is silently enlarged to accommodate native quads.

For failed visuals, retain a `WorldModels.Entry.placeholder` with the original state/problems and plan the atlas over successful visuals only. `AtlasBuilder.empty(selectedAssets, owner, cancelled)` now creates an explicit successful no-texture epoch for an all-empty/placeholder scene. It has zero admitted preparations, sprites, pages and primitive byte charges. It still requires the pinned selected-client profile, and does not fabricate a dummy mesh or texture. Binding an unadmitted mesh remains rejected. Empty `plan(List, ...)` is still an API error because it carries no asset receipt; use the explicit empty-atlas method.

Rasterization, section bounds/frustum culling, global transparent ordering, frame ownership and picking use the existing pipeline. Native source metadata reaches `MeshPacket.Face`, `AtlasMesh.Face`, `WorldMesh.Face` and the detached `WorldPick.Surface.source()` receipt. Original voxel palette/biome data and complete configured extents stay unchanged.

Native tint indices still require explicit `WorldTint` samples before GPU upload. Optional `WorldLighting` supplies final per-vertex modulation; emission/AO metadata alone does not create native lighting. Native cull labels are used only with supplied `WorldVisibility` masks; already-culled native output can omit cull labels to avoid applying another suppression rule. Alpha candidates still follow source sprites/force-translucent flags, and translucent scenes still require current camera-bound ordering. Native interpolation, filtering, shader/OIT and unsupported-special-visual limitations remain explicit.

## Bounds and failure behavior

| Resource | Default | Hard ceiling |
| --- | --- | --- |
| Native/composed quads | 24,576 | Same |
| Composed JSON selections | 1,024 | 4,096 |
| Output packed arrays | 16 MiB | Same |
| Composition source packet arrays + new packed output | 64 MiB | Same |
| Absolute block-local coordinate | 8 blocks | 64 blocks |
| Producer label | 256 characters | Same |

The eight-float vertex/six-index quad format remains 152 bytes per quad. Primitive-array limits do not count Java vertex/quad/list/string objects, selected archive bytes, decoded images, atlases, unrelated inputs or native generation. Quad/selection counts separately bound metadata. Capture retains no native snapshot arrays; the world/index/atlas stages continue their own combined admission. These are implementation bounds, not confirmation of total application memory or a change to preview area/height defaults.

Version, vanilla-only, missing-sprite, geometry, composition and budget failures publish no partial mesh/packet/bounds/selections. Cancellation propagates separately through quad validation, composition and packing. Empty supplied native geometry is valid and retains its capture binding. Neither success nor a placeholder enables an unsupported preview or modifies pack JSON.

## Deferred renderer-stage verification

At renderer completion, capture actual pinned native fluid/special geometry and compare vertex/normal/UV/winding/material results without state/biome substitution. Cover flat/sloped/nonplanar fluids, supplied back faces, waterlogged compositions, native empty output, degenerate triangles, overhangs, dynamic/missing sprites, external blocks and invalid input dimensions. Verify original bounds and all primitive/count thresholds, including zero-quad repeated composition and source-array peak admission.

Check detached immutable input data, position-dependent and explicitly independent reuse, foreign state/generation/capture UUID/position/asset epochs, and equal receipts on different captured arrays. Mixed JSON/native selections must keep original tickets, source pointers and per-face producer identity through texture/atlas/world packing, GPU passes and picking. Native hit receipts must not retain old snapshot arrays.

Exercise all-placeholder empty atlases, all-empty worlds, mixed successes/failures, no dummy texture upload, and marker rendering/inspection with native IDs intact. Verify tint and alpha guards, original visibility labels, current displayed animation alpha, global translucent order, cache replacement and complete-frame ownership. Inject capture/composition/texture/allocation/cancellation failures and ensure no partial visual layer is published. Native producers, full renderer/application adoption and visible Windows/native pixel validation remain unfinished; no tests ran in this batch.
