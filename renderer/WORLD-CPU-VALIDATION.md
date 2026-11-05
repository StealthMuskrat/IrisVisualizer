# Prepared world-pipeline CPU contracts

Prepared 2026-11-04. `WorldCpuContracts` adds 13 synthetic contract groups alongside the initial 18 map/scalar/image/ownership groups: 31 prepared groups total. Both harness sources compile and neither has been executed. No PASS results, renderer-stage completion, native parity or GPU readiness are claimed. Unsupported previews remain disabled.

## Reproducible supplied fixtures

The fixture creates a small detached test-only asset catalog containing a JSON blockstate, a six-face unit-cube JSON model and a standard-library-generated one-pixel RGBA PNG. It resolves, bakes, classifies, packs and binds these through the production CPU model/texture/atlas pipeline. Explicitly supplied native state/property-domain and biome identities remain original inputs. Synthetic source receipts and archive paths identify a fixture, never a verified selected client archive or real engine capture. No filesystem asset or native session is opened.

The one-cell section grid includes exact long world anchors beyond double's integer precision. Visibility is either explicit KEEP_ALL or a supplied six-bit neighbor mask. Alpha fixtures include opaque, intermediate and fully transparent values. Tight fixture-local atlas budgets prevent oversized page allocations. Existing production readiness and asset-selection rules are unchanged.

| Group | Prepared expectation |
| --- | --- |
| Native snapshot identity | Copied index arrays preserve state/property/biome identities; invalid indices and duplicate IDs withhold the entire capture |
| Model cube winding | Independently computed triangle cross products agree with outward normals, with all vertices on unit-cube boundaries |
| Atlas extrusion and binding | Known RGBA extends into edge/corner gutters; faces retain classification and exact admitted preparation identity |
| Visual assignments | Wrong native state and foreign-snapshot offsets fail atomically; caller assignment mutation cannot alter captured choices |
| Native visibility | KEEP_ALL retains interior faces; only supplied shared-face masks remove them; copied flags and undefined-bit rejection |
| Mesh metadata | Complete original section/cell/face coverage, material totals, range counts and per-face native identities |
| Cache ownership | Matching epochs share meshes; independent scene wrappers preserve shared tickets until last close |
| Complete limits | A one-face deficit publishes no partial scene; foreign visibility and cancellation remain explicit |
| Pinned replacement | A replacement cannot evict pinned old data or change cache stats on rejection; releasing old owners admits replacement |
| Geometry picking | Analytic unit-cube plane distance and exact source identities, batch/exhaustive agreement, clipping, budget failure and alpha/geometry modes |
| Translucent ordering | An independent vertex-centroid reference sort checks complete global back-to-front keys and stable ties; scratch deficit withholds the entire order |
| Empty/diagnostic visuals | Empty scenes use no dummy texture/geometry; unsupported markers preserve original state and diagnostic without fabricated block faces |
| Index ownership | Exact source-plus-index shared admission, cache shutdown with live scenes, consumed-owner release on close/failure/cancellation and independent original owner survival |

The winding, hit-distance and order expectations come from simple geometry and ordinary reference sorting rather than the prepared centroid/range metadata being checked. The batch/exhaustive query comparison supplements the analytic hit check. These fixtures exercise a narrow known model and are not a substitute for broader native/client-asset coverage.

## Compile and eventual execution

`./scripts/Validate-RendererCpu.ps1` continues to compile only by default. It now discovers both test sources and compiles them with the 88 dependency-free CPU production sources. Once renderer implementation is complete and stage execution is permitted, `-Execute` launches `RendererCpuContracts` and `WorldCpuContracts` in separate JVM processes and stops on either nonzero exit. A successful earlier suite cannot mask a later failure. No workflow was added or dispatched.

All 129 CPU/OpenGL production sources plus both harness sources compile with Java 25 and LWJGL 3.4.1 (131 total). The 88 CPU production sources plus both harnesses also compile independently without external dependencies (90 total). The Windows PowerShell runner was inspected only; no contract, native adapter, GL context, worker race, GPU upload/readback or workflow ran.

The [initial CPU validation scope](CPU-VALIDATION.md) remains explicit. Worker concurrency/failure injection, weighted/multipart/transformed models, complex native geometry and fluids, tint/lighting, animation, broad codecs, camera/state math and all real Windows GPU/native comparisons still need stage validation. Full source/client assets, combined application memory and final editor integration remain pending.
