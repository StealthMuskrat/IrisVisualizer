# Interpolated atlas playback

Renderer implementation, 2026-11-04. Compilation and source inspection only. No renderer tests, native execution, GPU context or workflow runs. Unsupported previews remain disabled.

## Sampling and pixels

`TextureSprite.sample` retains continuous scheduling metadata. `pixelSample` instead describes the implemented 26.3 pixel profile: elapsed integer ticks within the selected entry are divided by its duration using float arithmetic, then truncated to thousandths. Partial ticks remain validated but do not advance pixel playback. Large clocks use integer cycle modulo before local float conversion.

`PixelSample` retains current tile, next tile and blend steps. Zero progress and repeated tiles canonicalize to a single tile; a float-rounded full transition canonicalizes to its next tile. These values identify sampling inputs, not a claim that different source tiles necessarily contain different pixels.

`AtlasImages` blends corresponding encoded, unpremultiplied RGBA8 source texels with float weights and rounds each output channel to the nearest byte. Alpha participates alongside RGB. No gamma conversion, premultiplication, filtering or mipmap generation occurs. Content and gutters use the same blended edge/corner texels. Source images and previously published pages remain immutable.

This is a deterministic CPU base-level implementation of the reviewed operation. Native float normalization, shader arithmetic, texture format conversion and device rounding can differ by a byte; bitwise GPU parity is unverified.

## Complete handoff

Initial capture, patch generation and worker preflight all use the same `pixelSample`. `AtlasRaster.samples()` and `AtlasUpdate.samples()` retain complete canonical inputs. Their legacy `frames()` maps expose only the effective current tile and are insufficient for interpolation comparisons.

Progress changes can produce patches while the current tile stays unchanged. Request coalescing compares full tile/progress maps against the exact expected raster. Equal quantized inputs, partial-tick-only changes and repeated tiles avoid redundant work; seeking back to displayed inputs cancels obsolete work under existing revisions. Different inputs still trigger worker preparation even if their rounded output pixels happen to match. The connected [dirty rectangle path](ATLAS-DIRTY-RECTANGLES.md) then omits visually identical patches while advancing complete canonical receipts. Full-slot mode keeps its conservative patches.

`WorldVisuals` now admits supported interpolated sprites through shared texture/atlas preparation instead of replacing them with animation placeholders. Generic mapped-material diagnostics still retain possible translucent transitions. Baked JSON faces now follow the [reviewed raw source-UV alpha policy](MODEL-MATERIALS.md), and fluids follow whole-source groups; interpolation does not promote those declared passes. No native block/state/biome identity, geometry, material ordering or generation data changes.

The existing GPU uploader consumes completed RGBA patches without running a second blend. Changed pages receive private replacement textures; unchanged pages share ownership. Completed images retain their exact raster for alpha picking, and readback retains their clock receipts. No displayed texture or old image is overwritten.

## Admission and failures

Blending writes directly into the already-budgeted page or padded patch arrays; no intermediate tile images are allocated. Full-slot changed-sprite preflight includes progress changes and reserves padded patches plus one copy per changed page before worker submission. Dirty mode instead reserves a metadata upper bound within the declared ceilings, checks actual patch/copy costs before their allocations and shrinks successful completion admission to actual retained payload. Existing 32 MiB patch, 128 MiB atlas, 512 MiB atlas working and 1 GiB worker ceilings remain unchanged. Cancelled active work, ready plans and device retirement keep their existing ownership charges. Sample maps add bounded object metadata, excluded from primitive byte totals as before.

## Source review and pending validation

[Source receipts](interpolation-source-review.json) pin the reviewed shader asset mirror and SHA-256 values without vendoring shader bodies. Mojang's [25w44a notes](https://www.minecraft.net/en-us/article/minecraft-snapshot-25w44a) establish the GPU animation shader roles. The 26.3 fragment shader mixes RGBA; its vertex shader decodes thousandth progress. The client timing body reviewed in the previously documented unofficial source mirror supplies integer sub-frame progress. Mirrors do not authenticate the user's selected archive. The final native comparison must check that archive's actual shaders and timing implementation.

At renderer completion, compare mixed RGB/alpha endpoints, transparent RGB, rounding ties, unequal and maximum durations, thousandth boundaries, large-clock modulo, repeat/cycle/seek cases and float-rounded endpoints. Verify full recapture equals patch application, gutters, multi-sprite/page admission, cancellation, exact worker byte preflight, stale adoption, immutable old images and displayed-alpha picking. Compare tiny-budget GPU uploads/readback against CPU pages and the pinned native client. Native filtered/mipmap/UV behavior, special/fluid producers and final editor integration remain unfinished.
