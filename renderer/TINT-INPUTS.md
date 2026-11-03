# Native per-cell tint inputs

`WorldTintInput` carries explicitly captured native RGB layers from original cells through deterministic and weighted model preparation, shared prototypes, scene caching and GPU upload. `NativeTintInputs` supplies the typed native capture boundary. This implementation does not choose or verify a native color factory, colormap or blend policy. Unsupported previews remain disabled.

## Native provider and identity

The host supplies `NativeTintInputs.Provider`: the exact selected client receipt, a bounded policy label and a native sampler receiving the original `BlockState`, `Biome` and world `BlockPos`. It must freeze that resolver and its resources for the whole borrowed session and verify that its native resources, colormaps, blend radius and output match the selected archive. A label or matching archive hash does not prove color fidelity. There is no default tint algorithm or guessed fallback color.

Capture checks native session health, exact source receipt, selected archive SHA/version and the complete configured height. State IDs, original serialized properties/domains and biome IDs/keys round-trip through the actual registries. Every original cell is visited in X-fastest, Z, Y order at its exact native position. Capture does not alter native states, biomes, coordinates, height or generation. Session/resource failures, inconsistent schemas and exceeded bounds discard the whole output; cancellation propagates separately. Completion checks run before publication. Registries, callbacks, mutable positions, native handles and borrowed session owners are not retained by the detached result.

The provider may use its frozen native environment to obtain neighborhood-dependent results. Receiving a biome and position alone does not implement native blending or resource loading. Genuine native factory/output verification remains pending.

## Palette and coverage

A cell supplies zero to 64 `(layer index, RGB24)` pairs. Indices are in `0..65535`; colors are in `0x000000..0xffffff`. Empty coverage differs from true black. No alpha or white fallback is inferred for a missing tint layer. Tables are sorted, immutable and interned by both indices and colors. All cells with the same original state must expose the same layer-index schema; their RGB values may vary with biome or position. Inconsistent schemas fail atomically.

Model inspection consults that schema before accepting tinted geometry. Missing required layers produce original-state placeholders in both deterministic and selected weighted alternatives. An explicitly supplied native tint input that contradicts a direct mesh assignment fails assignment validation. Legacy no-input overloads preserve the missing-tint guard. Native fluid, waterlogged, entity and special-renderer guards remain active.

| Primitive storage | Bytes |
| --- | --- |
| Full-volume private table indices | 2 × cells |
| Table offsets and lengths | 8 × table capacity |
| Sorted layer indices and RGB values | 8 × layer capacity |
| First schema table per original state | 4 × state palette size |

Default/hard palette capacities are 65,536 tables (including empty table zero) and 65,536 aggregate stored layers. Payload is capped at 40 MiB, and selected catalog plus snapshot indices plus the prospective tint payload must fit a lowerable 512 MiB working bound. Capacity is allocated once and charged in full, including unused slots; there is no trimming copy or second full-volume index array. Palette/object metadata and native resolver scratch are count-bounded but are outside these primitive byte totals. The host still needs combined application admission.

## Model, worker and GPU handoff

`WorldModelInputs.Request`, `WorldModelChoices.inspect`, `WorldVisuals.Request` and `WorldModels` retain the same immutable tint input and check exact snapshot/assets identity. Native deterministic and weighted capture overloads accept it explicitly. `NativeTintInputs.weighted` captures it before weighted choices, offsets and visibility under the existing scene allowance. Pending inputs, assignment-copy preflight, source/cache epochs, meshing and GPU scene admission all include its actual allocated primitive bytes.

`WorldTint.fromInput(scene)` makes a uniform-per-layer view of the scene's existing palette. It maps emitted face provenance back to the original cell, supplies its layer RGB to all four vertices and returns white for untinted faces. It allocates no per-face color arrays. Its additional `bytes()` is zero because the palette is already charged in the retained scene source. `copySectionColors` explicitly creates a caller-owned expanded copy; that external copy requires separate admission.

The existing `WorldTint.capture` path still supports independently supplied, varying per-vertex native colors and charges its copied arrays separately. The new palette path does not establish native per-vertex blend parity.

`WorldRenderWorker.Completion.tint()` carries the shared view when captured colors exist. `GlWorldView.submit(completion)` transfers that owner and tint together. Existing upload APIs also bind the shared view automatically when no explicit `WorldTint` was supplied. No native calls or full color-array expansion run on the render thread. GPU preflight still rejects missing colors, packs explicit RGB bytes with alpha 255 and leaves texture alpha independent. Ownership, stale revision rejection and retirement follow the existing scene contracts.

## Deferred stage validation

All 99 independent CPU/OpenGL sources compile with Java 25 and LWJGL 3.4.1 (70 CPU, 29 GL). Ten native sources are syntax-parsed and manifest checked only; genuine pinned Windows native API type checking and execution remain pending. No renderer tests, native tint execution, GL context, pixel comparisons or workflows were run for this batch.

At renderer completion, cover empty/black/white values, maximum indices and layer counts, exact table/capacity limits, repeated tables, variable cell colors with stable schemas, inconsistent schemas, missing layers, exact snapshot/archive rejection, allocation failure, cancellation and completion faults. Exercise deterministic/weighted alternatives, shared prototypes and source-cell identity through offsets, visibility, sections, cache epochs, picking and RGB upload. Verify no duplicate source charge or per-face expansion in the shared path, manual per-vertex overrides, completion transfer/replacement/close and all lower working budgets. Compare actual pinned-native colormap/blending output and real Windows GPU pixels at biome boundaries and distant/full-height positions before enabling supported previews. Native resolver/factory selection, native fidelity, real-driver checks and final editor integration remain pending.
