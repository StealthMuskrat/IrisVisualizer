# Prepared world rendering GPU contracts

Prepared 2026-11-06. Five new `GlGpuContracts` groups extend the explicit Windows harness from shader-only world coverage to actual synthetic world uploads and completed-image rendering. All sources compile; **no assertion or GPU operation has run**. The harness now has 32 GPU groups alongside 87 CPU/context-free groups: 119 prepared, unexecuted groups across five harnesses. Renderer implementation, native/GPU validation and final integration remain pending. Unsupported previews stay disabled.

## Synthetic fixture boundary

`WorldGpuFixture` lives only under `renderer-gl/src/test/java`. Its package permits test-only construction of detached asset catalog entries, matching the earlier CPU fixtures. It does not change production APIs, load a selected client archive, download assets, open a pack, call a native generator or provide readiness evidence. Its original IDs, property strings, biome keys, generation receipt and JSON/PNG assets are explicitly synthetic.

The fixture exercises the ordinary model resolver, bounded geometry baker, texture/animation metadata decoder, shared atlas builder/binder, original-state assignments, explicit visibility masks, bounded scene plan and leased section cache. It retains exact integer coordinates beyond the double exact-integer range in both horizontal axes and supplies a complete one-block configured height. Neighbor masks are declared fixture inputs rather than an imitation of native face visibility. Empty/unsupported assignments use the real empty-atlas and diagnostic-marker paths, with no invented texture or block geometry.

Generated PNG bytes are tiny, deterministic RGBA fixtures. Cubes retain one shared prototype per declared original state. Atlas pages are bounded to eight texels per edge, and fixture scene planning has explicit section, face, cell, marker and memory limits. Lighting/tint providers carry explicit synthetic labels and grant no native lighting/tint fidelity. Source/cache owners close independently of transferred GPU scene and picking owners.

## New prepared groups

| Group | Independent expectations and boundaries |
| --- | --- |
| Textured geometry/original picking | Two neighboring white cubes have ten declared visible quads, sixty triangle indices and an exactly white center under supplied unit light. Disjoint clipping submits no geometry. Frame/readback/visual-assignment receipts and original long coordinates, state ID/properties and biome ID/key remain exact. CPU picking ownership survives GPU frame/scene retirement. |
| Cutout/ordered transparency | A two-texel cutout shows one transparent and one green half; displayed-alpha picking agrees while geometry-only picking retains the transparent face. Two transparent red/blue cubes require an exact camera-bound global order and produce analytical red-over-blue composition. Missing/mismatched order rejects before target admission. |
| Animated atlas ownership | A red/blue two-tile sheet stays red or becomes half-purple at tick one, depending on declared interpolation, and becomes blue at tick two. Exact tick receipts, bounded complete page copy/patch transfer, stale/closed baseline rejection, immutable old completed images and completed pixels after atlas-owner closure are prepared. |
| Material inputs/host state | Missing required tint consumes only the fresh CPU lease and allocates no device journal. The consuming final-lighting handoff rejects foreign tracked input with complete owner cleanup. White texture × supplied `0x804020` tint × `(1, 0.5, 0.25)` final light gives expected `(128,32,8)` channels. Exact lighting policy receipts, stale scene-bound material rejection and restoration of hostile draw/pack state are prepared. |
| Empty/diagnostic/recovery | Empty scenes allocate no dummy texture/mesh and submit no triangles. Unsupported original states render an explicit magenta marker and retain their native identity/problem in picking. Cancelling a partially stored world upload retires GPU work and releases its CPU lease. Invalid replacement preserves an untaken READY image; cancellation/retry and transferred frame ownership survive queue closure. |

Opaque, cutout and scheduled/interpolated texel expectations are exact RGBA bytes. Analytical transparent composition allows two byte levels per RGB channel for quantization across two framebuffer blends, and tint/final-lighting multiplication allows one. Shader/clear-state errors cannot be hidden by broadly tolerant pixel comparisons. The cutout test compares its opaque/clear halves independently of horizontal face-UV orientation and then checks original-value picking against the displayed result; it does not establish native model UV parity.

Every world upload uses the ordinary incremental API with the harness's minimum transfer/action/work budget. It requires complete validation/section/byte progress and rejects incomplete take or duplicate transfer of the exact device-owned CPU lease. Atlas updates independently enforce their combined copy/upload allowance. The existing per-group bounded device/admission cleanup checks remain in force. The fixture is test-only. The later context-free world-culling group also explicitly shares this CPU-only fixture through the dependency-free runner; the GPU harness remains excluded.

## Build and later execution

The [Windows GPU runner](GPU-VALIDATION.md) remains compile-only by default. Its existing Gradle test source set automatically includes the fixture and five new groups; no workflow or execution default was added. Successful execution evidence requires all 18 groups and context cleanup, rather than accepting the earlier eleven-group count.

Actual preparation here: Java 25/LWJGL compilation passed for 136 CPU/OpenGL production sources, five harness sources and one test fixture (142 files). The 104-file dependency-free set also compiles; it includes the CPU-only fixture and excludes the GPU harness. No test main, fixture constructor, synthetic rendering, GLFW initialization, native adapter, shader, readback, Gradle/PowerShell runner or CI workflow was executed. Compilation establishes type compatibility only.

The synthetic world cases still require real Windows GPU execution. They do not prove independent native world agreement, selected-client asset fidelity, native special visuals/lighting/tint factories, broad model/UV/texture coverage, large-world face-range differential rendering, every animation/alpha material, connected world worker races, fence/quarantine/restoration fault injection, cross-context behavior, total application memory, measured performance or interactive editor/DPI integration. Keep those remaining renderer/integration checks explicit before enabling a supported preview.

[Shared world face-group culling](TRANSLUCENT-CULLING.md) now adds all-translucent/mixed differential pixels, cached decision counts and deficient draw-scratch admission to the prepared GPU matrix. This remains preparation without real-driver execution.

[Bounded live playback](LIVE-PLAYBACK.md) adds two GPU groups for continuous clocks, stable admitted work, initial presentation, pause/seek/mode changes, navigation, material/world replacement and zero-budget recovery. Full and dependency-free compilation pass (143/105 files). Current totals are 105 prepared, unexecuted groups: 77 CPU/context-free and 28 GPU. Native/GPU validation and final integration remain pending.

[Transparent material-order reuse](MATERIAL-ORDER-REUSE.md) adds three GPU groups for independent exact-scene rebinding, raw/owned connected reuse and destination-admission recovery. Full/default compilation covers 143/105 files; all 108 prepared groups remain unexecuted (77 CPU/context-free, 31 GPU). No real-context, native or final integration result is claimed.

[Sparse atlas clock observation](../renderer/SPARSE-ATLAS-CLOCKS.md) adds one GPU group. Current full/default compilation covers 143/105 files; all 112 prepared groups remain unexecuted (80 CPU/context-free, 32 GPU). Native/GPU validation and final integration remain pending.

[Original-range picking hierarchy](../renderer/PICKING-RANGE-HIERARCHY.md) extends the existing shared-culling GPU group with completed-frame tree/linear-group/exhaustive comparison, exact displayed atlas/clip controls, original state identity and atomic query-budget failure. Current totals are 115 prepared, unexecuted groups (83 CPU/context-free, 32 GPU); compilation covers 143/105 full/default files. Native/GPU validation and final integration remain pending.

[Implicit section culling](SECTION-CULLING.md) adds a prepared irregular full XYZ hierarchy/linear/disabled GPU comparison, retaining source identity, transparent ordering, marker coverage and completed output under draw-budget rejection. All 160 groups (109 context-free, 51 GPU) remain unexecuted; full/default source compilation passes (144/106 files).
