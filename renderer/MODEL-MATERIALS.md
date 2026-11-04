# Model source alpha and terrain fragment thresholds

Renderer implementation, 2026-11-04. All 119 CPU/OpenGL main Java sources compile with Java 25 and LWJGL 3.4.1. Sixteen native adapters pass syntax parsing only; genuine pinned Windows dependency compilation remains pending. No renderer tests, native execution, shader compilation, GL contexts or workflows ran. Unsupported previews remain disabled.

## Source UV scope

The reviewed 26.3 `FaceBakery` classifies a model material before rotating its UV corners or applying UV lock. The baker now retains each explicit/default source rectangle in `ModelPlan.Material.alphaUv` on the emitted face. Mirrored endpoints remain ordered in that receipt; classification sorts their extrema. Geometry, normals, source UVs, cull labels, selections and original generated state/biome identity are unchanged.

The field is optional and immutable. It excludes a simultaneous whole-source group and requires finite coordinates within 0..16. Existing material constructors remain available. Ordinary parsed JSON materials acquire this scope during geometry baking; parsed texture bindings and particle declarations remain unchanged. Explicit whole-source groups retain their existing policy when baking a caller-supplied plan. Native fluid groups still union still/flow/overlay sources. Supplied native geometry without either explicit scope keeps conservative mapped-region behavior.

Texture preparation implements the reviewed `SpriteContents` decision using the selected decoded sheet. A wholly opaque sheet or full source rectangle uses the complete sheet summary, including unused tiles. Other rectangles use native float multiplication, floor lower edges and exclusive ceiling upper edges over distinct effective frames. Exact pixel boundaries therefore exclude the adjoining texel. A rectangle with zero rounded width or height has no alpha samples and selects opaque unless forced translucent; it does not invent an opaque pixel. Positive subpixel rectangles still cover their floor/ceiling interval. Static metadata schedules retain the existing top-left tile policy.

This source pass uses raw alpha categories without promoting binary interpolation to translucent. `forceTranslucent` remains authoritative. `TexturePreparation.Face.alpha` and `region` continue to report the separate conservative, final mapped-region scan; `materialAlpha` reports the declared source policy. These summaries can differ after UV lock, at exact boundaries or during interpolation. Constructor checks require the optional summary to match its scope and published class. The mapped summary must remain nonempty; an empty source summary is permitted. Whole-source group summaries must still include their mapped raw categories.

## Drawing and inspection

The shared `AlphaClass` policy now supplies the reviewed terrain thresholds to both generated GLSL and CPU displayed-alpha picking:

| Pass | Discard below normalized alpha | First accepted RGBA8 alpha |
| --- | --- | --- |
| Opaque | No alpha discard | 0 |
| Cutout | 0.5 | 128 |
| Translucent | 0.1 | 26 |

Previously translucent drawing and picking rejected only zero alpha. Both now reject 0..25 and accept 26..255. The GLSL constants are formatted from the same enum values used for the byte acceptance calculation. No extra uniform, vertex field or per-fragment material buffer is added. RGB tint and supplied/simple lighting do not alter texture alpha. Existing straight-alpha blending, depth policy, global centroid order and immutable image/readback ownership remain in place. This implements the reviewed threshold, not native OIT, filtered sampling, lightmap or pixel parity.

Source classes flow through existing atlas binding, world ranges, translucent sorting and draw plans. Animation retains the chosen pass while displayed-alpha picking samples the exact displayed raster. A source region may select opaque even if UV lock maps it over different alpha pixels; this follows the reviewed source decision, and the solid pass/picker consistently apply no alpha rejection. Original face geometry and source provenance remain available for inspection.

## Admission and cache bounds

Mapped, model-source and whole-sheet scans use distinct scope keys in one cache. They share the unchanged 8,192 scan and 64 Mi pixel-visit ceilings. Whole sheets are charged once by width times height; source regions cost their area times distinct effective frames. Per-mesh usage includes all unique scan keys it requires, while batch usage counts actual cached work once. Opaque/full-region fast paths reuse the sheet entry; empty source rectangles allocate no scan entry and cost no pixel visits. Existing fluid group metadata has its separate bounded cache.

Sheet scans add no decoded pixel copy and stay within existing encoded, RGBA, atlas and scene admission. Source UV records add bounded per-face object metadata, excluded from primitive byte budgets as before. No packed/GPU stride or resource ceiling changes. Cancellation, missing/malformed assets and exhausted budgets withhold complete results through the existing standalone/shared error paths. There is no cross-archive global cache.

## Evidence and deferred validation

[Source review receipts](model-material-source-review.json) link the official pinned NeoForge `FaceBakery` patch, complete decompiled `FaceBakery`/`SpriteContents`/`NativeImage`/`RenderPipelines` bodies and a pinned terrain shader asset mirror. The official patch corroborates source classification before bake; pipeline constants and shader behavior were inspected through unofficial reproductions. They do not authenticate the user's selected client or establish native API compatibility. No source bodies or assets are bundled.

At renderer completion, compare all dispatch quarter turns, UV rotations and UV-lock combinations with asymmetric alpha regions. Include mirrored/default UVs; exact boundaries and nearby float values; zero/positive subpixel extents; opaque fast paths; partial versus full source windows; unused animated tiles; binary-mask interpolation; force translucency; generic native mapped materials; fluid groups; and mixed waterlogged JSON/fluid composition. Verify source receipts through packing/composition/atlas/world ranges, original-state picking and immutable old frames. Exercise standalone/shared agreement, repeated region/sheet caches, byte/scan/pixel limits, cancellation and whole-result failure. Check RGBA8 alpha 0, 1, 25, 26, 127, 128 and 255 against real rendered pixels, depth/blending and displayed-alpha picking. Compile genuine Windows native dependencies and execute the complete renderer stage suite only after implementation is complete; application integration follows that stage.
