# Iris Visualizer

Standalone Windows x64 visualizer and generation-focused pack editor for Iris, targeting Java 25, LWJGL and ImGui.

**Status: UI implementation and automated validation complete; renderer foundation in progress; unsupported world previews disabled.** All 176 editor regression groups, pinned source/policy checks, real 8,112-file Overworld edit/storage acceptance, Windows package/launcher checks and all five native feasibility suites passed. The pinned inventory accounts for 1,462 fields across 150 models, with 1,237 implemented generation fields, 225 read-only fields and no unclassified policies. Interactive Windows GUI/DPI/GPU verification remains unverified; complete native-world parity has not been established. See [the final UI validation record](docs/UI-VALIDATION.md). The separate [renderer module](renderer/README.md) now captures version-matched user-selected client archives and resolves bounded blockstate/model plans with inherited materials and original native-state identity; geometry baking, GPU work and renderer-stage testing remain pending.

See [the editor guide](editor/README.md) for build/run instructions. Download the [validated Windows x64 ZIP artifact](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/41521313042/artifacts/13177583655) from the [passing editor run](https://github.com/StealthMuskrat/IrisVisualizer/actions/runs/41521313042). Install Java 25 x64 separately; no runtime is bundled.

## Verify the first batch

Install Git and a Java 25 x64 **JDK** for development. End users of the eventual desktop distribution will need only the Java 25 x64 runtime; no runtime will be bundled or installed automatically.

From PowerShell 7 on Windows x64:

```powershell
./scripts/Prepare-Sources.ps1
./scripts/Test-Feasibility.ps1 -NativeCapabilities
```

Preparation checks out the exact Iris, VolmLib and Overworld revisions in [iris.lock.json](iris.lock.json). Verification builds the official Iris sources with the additional probe sources, generates representative full-height chunks for two seeds, repeats them in fresh sessions, checks source-pack bytes, and runs selected upstream native feasibility tests. Test output is written under `build/feasibility/` and is never used as a recovery copy of an edited pack. The official helper's disposable input workspace is generation scratch space, not a user file-history feature.

The test harness needs network access to public Gradle and upstream Maven repositories. Missing dependencies, invalid current packs, generation errors, height/budget violations and nondeterminism fail verification. The workflow intentionally does not turn an incomplete generation result into a passing preview.

The native registry/terrain proof has a separate [run guide and scope](docs/NATIVE-FIXTURE.md). The [Overworld context audit](docs/OVERWORLD-CONTEXT.md) records native requirements and blocks incomplete world previews. The next [native structure planning proof](docs/OVERWORLD-STRUCTURES.md) checks configured starts, ownership and references separately. The [block-service audit](docs/NATIVE-BLOCK-BOUNDARY.md) then exercises the native preparation and piece calls in disposable chunks. The [template-service adapter](docs/NATIVE-TEMPLATE-SERVICES.md) supplies the context template manager to native carving without a server-world lookup. The [piece dispatcher](docs/NATIVE-PIECE-DISPATCH.md) then calls native pieces and their after-placement stage in the standalone context without Bukkit plugin callbacks. The [integrated native stage](docs/INTEGRATED-NATIVE-STRUCTURES.md) combines those services with the official foundation/stilt, repair and invalidation pipeline.

Read [the feasibility findings](docs/FEASIBILITY.md) and [the development batches](docs/DEVELOPMENT.md) before continuing. The full [project specification](docs/SPECIFICATION.md) is retained in the repository. The two UI images supplied with the request guide the later desktop layout. The [UI completion checklist](docs/UI-COMPLETION.md) tracks the entire current UI stage against the original brief; full GitHub testing is deferred until that stage is complete, before renderer work begins.

## Native comparison

`BaselineComparison` accepts a candidate properties report and TSV, followed by an independently captured native report and TSV. It rejects mismatched source revisions, pack fingerprints, dimensions, heights, coordinates, seeds, compatibility-overlay hashes and block/biome hashes. A second stub run is **repeatability**, not a native baseline. Baseline capture is still outstanding; the comparator is not a native exporter.

## License

GPL-3.0-or-later; see [LICENSE.md](LICENSE.md). Iris is developed by Volmit Software under GPL-3.0-or-later. The integration compiles against upstream sources rather than duplicating Iris generation algorithms or schemas.

The editor also authors optional dimension world borders: paired center coordinates, full diameter, warning distance and damage settings, using pinned native bounds and preserving advanced data. Null/absence leaves the live native border unchanged; the editor does not apply borders to running worlds. Unsupported previews remain disabled.

Dimension-type editing includes environment/fullbright and all 13 pinned options, explicit base inheritance and nullable time/cloud behavior, and exact 64-bit fixed times. Runtime registry and existing-world compatibility remain unvalidated; unsupported previews remain disabled.

Noise-fracture trees now support bounded recursive child editing across composite, cliff and placement-heightmap contexts, including lossless list operations and shared noise/style controls. Editor limits are explicit. Unsupported previews remain disabled.

Style-fracture objects now have bounded recursive controls across existing style contexts, with FLAT child defaults, source/cache settings and independent child multipliers. Deeper or unsupported data is preserved, and unsupported previews remain disabled.

Dimension stacking now supports staged ordered layers, native host/mutual-exclusion checks, exact spacer/blend controls and shared recursive style editing. Upper-terrain references are authorable; target content and native layout/generation remain unvalidated. Unsupported previews remain disabled.

Custom-biome definitions now have bounded climate, color, enum, tag, particle and spawn controls with lossless nested list editing. Registry/datapack/version compatibility remains unvalidated, and unsupported previews remain disabled.
