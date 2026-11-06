# Captured-neighbor water material colors

Renderer implementation, 2026-11-06. Compilation/source inspection only. Renderer-stage execution, genuine pinned Windows compilation and native/GPU comparisons remain pending; unsupported previews remain disabled.

## Explicit water policy

`NativeFluidInputs.Limits` now accepts optional `WorldBiomeBlend.Limits`. Existing three-argument construction and `DEFAULT` retain radius-zero native water colors. To enable bounded horizontal averaging in either `NativeCapture.fluidScene` or the existing ready-input `NativeCapture.fluids` route:

```java
var fluids = NativeFluidInputs.Limits.DEFAULT.withWaterBlend(
    new WorldBiomeBlend.Limits(2, 64L * 1024 * 1024));
```

Pass the complete borrowed native neighbor snapshot through the existing `neighbors` parameter. This option does not generate or fetch neighbors, expand output volume, change height or establish readiness. No colormap is needed for water: each palette color comes from the actual native biome's `getWaterColor`. Block-model tint options remain independently configurable.

The shared [biome blending helper](BIOME-BLENDING.md) validates the exact generation receipt, complete configured height, full target-plus-radius coverage and original biome identity at every overlapping cell. Native water capture additionally validates each halo palette ID/key against the current native registry and restricts the halo to signed native X/Z. Palette ordering may differ. Missing or inconsistent halo data produces a structured failure; center-biome substitution and edge clamping are forbidden.

RGB channels are averaged independently at the original cell Y. Radius 0–7 and a positive cumulative sample limit bound all color work; geometry's existing native-neighbor query limit remains independent. Each visible eligible water cell reserves its complete color footprint before callbacks, including cells whose completed recipe can reuse a geometry prototype. Fully hidden fluid cells consume no color samples. Lava remains white with its original emission and consumes no water-color samples. An explicitly configured blend still validates its halo/palette before traversing cells, including a lava-only or empty volume.

## Admission and detached output

An enabled water blend retains one palette-sized `int[]`, at four bytes per halo biome (32 KiB at the existing maximum). The effective fluid-edit working allowance subtracts this palette in addition to borrowed neighbor indices before native color reads, state palette construction, assignment copies or new packets. Both the parent request ceiling and the independently configured fluid-edit ceiling apply. Combined capture continues to recognize its already-reserved neighbor indices, while the standalone route reserves those indices itself. The parent capture's existing peak is respected; no memory ceiling is increased.

If the model-colormap options and fluid options borrow the same halo, their existing conservative reservations can charge its indices twice. Keep each borrowed source owner alive through synchronous capture and use the existing shared coordinator contract. Native/registry/object overhead remains part of the host's external allowance.

Only averaged RGB enters `FluidGeometry.Cell`, so existing exact-recipe cache keys distinguish different colors and continue sharing identical complete recipes. Waterlogged bases retain their exact selected model tickets, block tint indices and native offsets. Fluid quads retain tint index -1 and cell-origin placement, including top/reverse/side/overlay faces. Their material RGB is independent of model tints; material alpha groups, atlas animation and existing GPU vertex formats are unchanged. No halo, palette array, blend helper, native biome handle, options object or provider escapes into the completed request.

## Accurate color provenance

The native visual producer's policy now records radius-zero or configured-radius water color. A new `FluidGeometry.bake` overload accepts an explicit bounded color-policy string and carries it into `NativeMeshSource` on every quad. Native capture uses that overload, recording the selected water policy or white native lava. The earlier low-level overload remains available and identifies caller-supplied water/lava material RGB without inferring a sampling radius from the RGB value. Policies must be nonblank and at most 128 characters, keeping the complete native provenance receipt within its existing bound.

## Prepared verification

The new world contract group prepares a palette boundary average flowing through fluid geometry, forward/reverse/overlay RGB, original waterlogged state/snapshot identity, cell placement, complete packed bytes, whole-water alpha classification and atlas color/provenance. It also prepares ordered JSON-base/fluid composition with independent tint indices/colors/placement, white lava/emission, compatibility provenance, missing sprite rejection, exact and deficient packet budgets, policy bounds and cancellation. The four harnesses now contain 70 prepared groups, including the later [visual-report](VISUAL-REPORTS.md) and [background-report](REPORT-WORKER.md) groups; none has been executed.

All 134 independent CPU/OpenGL production sources and four harnesses compile with Java 25/LWJGL (138 files), as does the 101-file dependency-free set. All sixteen native adapters syntax-parse and their source-manifest hashes match; this does not resolve native types or execute their code. At renderer-stage validation, additionally exercise genuine registry/key disagreement, palette/sample/working limits, cancellation, parent/standalone reservations, repeated/failed edits and failure without partial output. Compare actual selected-client biome sampling and water colors across boundaries, custom biome effects, waterlogged models and visible GPU/readback output. No native/client pixel parity or completed editor integration is claimed.
