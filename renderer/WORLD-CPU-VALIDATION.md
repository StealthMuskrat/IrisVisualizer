# Prepared world-pipeline CPU contracts

Prepared 2026-11-04; updated 2026-11-05. `WorldCpuContracts` has 21 synthetic contract groups alongside 19 map/scalar/image/ownership groups: 40 prepared groups across those two suites. The separate [display math suite](../renderer-gl/MATH-VALIDATION.md) adds 13 groups, and [animation contracts](ANIMATION-OWNERS.md) add 11 for 64 total. All four harness sources compile and none has been executed. No PASS results, renderer-stage completion, native parity or GPU readiness are claimed. Unsupported previews remain disabled.

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
| Cache ownership | Matching meshes and independent shared wrappers; cancellation/runtime/error with completed plus incomplete tickets, full rebuild, final charge/account release; retirement preparation OOM/Error preserves mixed pinned/idle/planned state, successful retry keeps exact tickets/counters, and post-commit publication errors still attempt account closure |
| Complete limits | A one-face deficit publishes no partial scene; foreign visibility and cancellation remain explicit |
| Pinned replacement | Pinned data survives pressure rejection and tentative cache-entry insertion failure; exact counters/admission, suppressed rollback errors, one-lease retry and original native metadata remain intact |
| Geometry picking | Analytic unit-cube plane distance and exact source identities, batch/exhaustive agreement, clipping, budget failure and alpha/geometry modes |
| Conservative picking hierarchy | Analytic edge/corner hits survive node/range rejection; six exact source planes and tiny translated planes retain detached coordinates; section-local tiny inside/outside points, six marker entry planes, inside-box near distances, closed clip/ray endpoints, strict exclusions, shifted anchors, lower-section ties, atomic budget failure and unexpanded actual marker boxes |
| Translucent ordering | An independent vertex-centroid reference sort checks complete global back-to-front keys and stable ties; scratch deficit withholds the entire order |
| Translucent view preflight | Tiny/subnormal directions preserve pending/READY status, tokens and admission; fresh rejected wrappers are consumed, existing owners protected, and the exact minimum direction keeps the ordinary axial order |
| Empty/diagnostic visuals | Empty scenes use no dummy texture/geometry; unsupported markers preserve original state and diagnostic without fabricated block faces |
| Index/capture ownership | Exact source-plus-index admission and independent owners; worker close failures block stale take, original preflight/prepare/query errors survive source cleanup, and owned lighting/colormap producer errors survive capture-hold release; READY scene/render disposal failures detach closed output/tint, remove uninstalled replacement reservations, retain actual cache charge and permit complete retry |
| Pending sort admission | Insufficient overlap preserves the exact source/status; admitted replacement releases old ownership; duplicate wrappers stay protected and close clears local/shared bytes |
| READY sort admission | Known twelve-face scratch shrinks to source plus final keys; old output survives rejection and exact old-output plus new-peak capacity permits replacement |
| Transferred sort admission | Taken completion and independently retained holds stay charged through rejected submissions; final close permits retry without skipped revisions |
| Active sort cancellation | A controlled active source read preserves local/shared peak through rejection and nonblocking close; final unwind releases only the worker source and its account |
| Scene-worker shutdown recovery | Controlled executor shutdown RuntimeException/Error leaves source/cache cleanup and CLOSED guards intact; second close retries, successful close is idempotent and transferred scenes stay charged; constructor RuntimeException/OOM/Error and null factory/executor cases preserve primary errors through distinct/identical account-close faults, unrelated admission and retry |
| Worker startup, drain and shutdown recovery | All twelve startup paths preserve rejection/OOM/Error semantics and retry; controlled sort/index active errors preserve primary/suppressed identity, newer pending work, lease/admission cleanup and later complete output; world active cache/input peaks enforce local exact/over boundaries for ready/model pending inputs with and without tracking, preserving rejected work and charging active unwind; eight preparation/pointer families also retry failed shutdown and preserve transferred output |

The winding, hit-distance and order expectations come from simple geometry and ordinary reference sorting rather than the prepared centroid/range metadata being checked. The batch/exhaustive query comparison supplements the analytic hit check. These fixtures exercise a narrow known model and are not a substitute for broader native/client-asset coverage.

## Compile and eventual execution

`./scripts/Validate-RendererCpu.ps1` continues to compile only by default. It includes the three CPU harnesses plus the separate context-free math suite and four display helpers, alongside 88 dependency-free CPU production sources. Once renderer implementation is complete and stage execution is permitted, `-Execute` launches all four suite classes in separate JVM processes and stops on any nonzero exit. A successful earlier suite cannot mask a later failure. No workflow was added or dispatched.

All 129 CPU/OpenGL production sources plus four harness sources compile with Java 25 and LWJGL 3.4.1 (133 total). The default source set, including four display helpers and four harnesses, compiles without external dependencies (96 total). The Windows PowerShell runner was inspected only; no contract, native adapter, GL context, worker race, GPU upload/readback or workflow ran.

The [initial CPU validation scope](CPU-VALIDATION.md) remains explicit. Broader worker concurrency/failure injection beyond the prepared controlled sort and worker-shutdown groups, weighted/multipart/transformed models, complex native geometry and fluids, tint/lighting, animation, broad codecs, camera/state math and all real Windows GPU/native comparisons still need stage validation. Full source/client assets, combined application memory and final editor integration remain pending.
