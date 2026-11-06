# Captured-neighbor biome tint blending

Renderer implementation, 2026-11-06. Compilation and source inspection only; renderer-stage execution remains deferred. Unsupported previews remain disabled.

## Exact input and bounded work

`WorldBiomeBlend` performs horizontal square averaging of RGB channels at the requested Y. Radius zero reads one sample; radius seven reads 225. Each channel uses integer division of its sum, and alpha is discarded. There are no intermediate pixel tables or global caches. The helper is capture-local and thread-confined.

The target and borrowed halo must be successful immutable `WorldSnapshot` values from the exact same `Source`, with identical minimum Y and complete height. The halo must cover the entire target plus the configured horizontal radius. Every overlapping cell must retain the same native biome ID, original key and parsed asset ID. Palette ordering may differ. Missing samples are never clamped or replaced with the center biome. An upper-half plant requesting a biome below the captured minimum fails explicitly.

World-coordinate expansion is checked before callbacks. Index arithmetic subtracts the source origin before combining offsets, retaining exact signed-long coordinates without floating-point conversion. Native capture additionally restricts its halo to signed native coordinates.

`Limits` accepts radius 0–7 and a positive cumulative sample allowance of at most 268,435,456. A complete square must fit before the first callback. Its entire sample cost is reserved before sampling, including when a callback fails or cancellation interrupts it. Cancellation is checked before setup, periodically during overlap validation, before every sample and before returning a color. Callback failures escape; no partial color is returned. The ordinary `WorldTintInput.read` boundary converts structured failures to an empty diagnostic result, including failure after an earlier cell succeeded.

## Native colormap capture

The existing `vanillaUnblended(maps)` options retain radius-zero behavior. To select explicit horizontal blending:

```java
var options = NativeCapture.WorldOptions.vanillaBlended(
    maps, capturedHalo, new WorldBiomeBlend.Limits(2, 64L * 1024 * 1024));
```

Keep the colormap owner and immutable halo owner alive through the synchronous capture. The caller continues to own/freeze the native session. The target is captured through the existing deterministic, weighted or combined fluid-scene path; this option does not expand generation, fetch chunks or establish readiness. Previous two- and three-argument `WorldOptions` constructors remain available.

`NativeColormapTints` validates each halo palette entry against the actual native registry and derives its climate/override colors through the existing selected-client colormap path. Grass modifiers run at each sampled neighbor's own X/Z, before channel averaging. Grass, foliage, dry foliage and water-cauldron colors use the selected radius. Fixed and state-dependent tint sources retain their existing values, and flower tint-layer schemas remain unchanged. A tall plant's upper half samples the complete square one block below.

The policy receipt records the selected radius. Only ordinary detached `WorldTintInput` output enters scene construction; no halo, native biome handle, codec graph, sampler, options object or blend helper escapes into the worker request. Block-model blending remains independent of the separate [optional fluid material-color blend](FLUID-COLORS.md).

## Memory and validation boundaries

The borrowed halo's complete state and biome index payload is counted with borrowed colormap bytes in native volume admission, and remains subtracted from the scene phase's working ceiling. Tint preflight includes that halo, the source maps, private native utility map copies, four RGB arrays sized to the halo biome palette, the target indices and prospective tint output. No configured height, area, memory ceiling or shared coordinator is relaxed. As with other borrowed inputs, an already tracked halo owner must stay alive; conservative duplicate admission is possible, including when a separate fluid-neighbor option references the same halo.

The prepared world contract group covers palette reordering, native-key disagreement, generation/height/coverage rejection, exact radius-zero/one/seven arithmetic, position-dependent neighbor colors, signed-long edges, cumulative query limits, callback errors, cancellation and failure without partial tint tables. It also prepares complete ordinary tint capture with retained native snapshot/state identity. The four harnesses now contain 70 prepared groups, including the later [fluid-color material](FLUID-COLORS.md) [visual-report](VISUAL-REPORTS.md) and [background-report](REPORT-WORKER.md) groups; none has been executed.

All 134 independent CPU/OpenGL production sources and four harnesses compile with Java 25 and LWJGL (138 files). The dependency-free set also compiles (100 files). All sixteen native adapters syntax-parse, and their manifest hashes match. Native syntax parsing does not resolve native types or execute the adapters. Genuine pinned Windows compilation, selected-client comparison of the averaging policy and biome sampling, native/GPU color comparisons, concurrency checks and final editor integration remain pending. No client pixel-parity claim follows from this implementation.
