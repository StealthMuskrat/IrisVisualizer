# Selected-colormap vanilla world-model tints

Renderer implementation; compilation and source inspection only. Runtime tests remain deferred until renderer implementation is complete. Unsupported previews remain disabled.

## Client resources

`ClientColormaps.read` uses the existing bounded PNG decoder for `minecraft:colormap/grass`, `foliage` and `dry_foliage`. Every image must be exactly 256×256. All three must succeed before any table is published. PNG metadata does not animate these tables. Source archive/version and individual PNG digests remain available; later source edits do not alter captured pixels.

Retained tables contain native-order ARGB pixels, preserving decoded channels and alpha. `copyArgb` returns detached arrays. There is no global installation, source write, image substitution or selected-archive class loading. The three retained arrays cost 786,432 bytes. The decoder preflights the captured catalog, complete output, one RGBA image, bounded encoded copy and 64 KiB primitive scratch. Aggregate encoded input defaults to a 4 MiB hard ceiling; working payload defaults to 512 MiB and can be lowered. Decoder/VM native overhead and object headers remain outside primitive totals.

`readOwned` optionally admits that peak through `RenderMemory` before decoding, then shrinks to retained table bytes. Keep the resulting owner alive while using its borrowed maps. Failure publishes structured asset diagnostics with no partial tables; cancellation propagates. Missing maps are a limitation of this tint option and do not invalidate unrelated catalog uses.

## Native source

`NativeColormapTints.capture` reads climate through the actual biome network codec once per captured biome. It clamps native float climate values, invokes the native `ColorMapColorUtil` against private map copies, honors native color overrides and invokes the native positional grass modifier. It calls native redstone color evaluation and reads actual native stem/plant properties. It neither replaces generation algorithms nor mutates native global colormap tables.

| World-model source | Tint layers |
| --- | --- |
| Grass block, fern, short grass, potted fern, bush, sugar cane | Grass at current captured biome/position, index 0 |
| Large fern, tall grass | Upper half resolves the captured biome one block below |
| Pink petals, wildflowers | White index 0 plus grass index 1 |
| Oak/jungle/acacia/dark-oak/mangrove leaves and vine | Native biome foliage override or selected foliage map |
| Leaf litter | Native dry-foliage override or selected dry-foliage map |
| Water cauldron | Actual native biome water color |
| Spruce/birch leaves, attached stems, lily pad | Reviewed vanilla in-world constants |
| Redstone wire, growing stems | Original native power/age-dependent colors |

Unknown tint sources return no layer, preserving existing unsupported-model handling. Particle-only water/bubble-column registrations deliberately supply no fluid-mesh layer. Fluid geometry and its color contract remain separate unfinished work. Terrain-particle and inventory colors are outside this world-model profile.

This is explicitly **biome blend radius zero**. It does not clamp missing neighbors to the current biome or claim neighboring-biome averaging. A tall-plant source requiring Y below the captured extent fails explicitly. Full configured height, original native IDs, palette identities and healthy/frozen session checks remain required. Native codec graphs are temporary native metadata, excluded from primitive byte counts; the host must include native/codec overhead in its external working allowance.

Rules are cached per original native state and identical output layer tables are shared within the existing table ceiling. The output is the ordinary immutable `WorldTintInput`; no native biome, state handle, codec graph, provider or private native map copy escapes it. Existing GPU tint upload, material passes and frame/picking identity contracts apply.

## Complete capture

Load matching colormaps, then pass `NativeCapture.WorldOptions.vanillaUnblended(maps)` to the existing deterministic or weighted capture. The options reject mixing this source with an explicit provider. The original constructor/provider path remains available for other verified color/blending policies.

Volume admission includes borrowed table bytes. Tint admission includes source tables, native utility copies, four palette RGB arrays and prospective tint output before native color work. Subsequent scene capture reserves space for the still-borrowed maps inside its existing working ceiling. No generation area, height or renderer memory limit is increased or silently reduced. Keep source owners alive through the operation; use the same shared coordinator for tracked catalog/map owners. Returned worker requests retain only detached output.

## Review and pending checks

[Source receipts](vanilla-tint-source-review.json) record the review. The pinned official NeoForge patch corroborates the native registration shape; complete method bodies were inspected in an unofficial 26.3 source mirror. Neither authenticates user assets or establishes native API compatibility. No upstream source bodies or vanilla PNGs are vendored.

At renderer completion, compile against the genuine pinned Windows dependencies and compare every registered world-model source against the actual selected client at radius zero. Include colormap corners/climate clamps, custom native biome overrides/modifiers, upper/lower plant halves and biome boundaries, both flower layers, every redstone power and stem age, alpha/channel ordering, absent layers, source/version mismatch, malformed PNGs, immutability, cancellation, codec failures and all byte/table limits. Confirm deterministic/weighted input, GPU/readback color agreement, native state/biome inspection and failure without partial output. Neighbor blending, fluid geometry, final editor integration and visible GPU QA remain unfinished. No renderer tests or workflows ran for this batch.
