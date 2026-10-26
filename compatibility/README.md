# Native compatibility adapters

The filesystem helper in `volmlib/src/main/java/` is an unchanged copy of upstream VolmLib's `IOWorkerSupport.java` from commit `0eb8a9282cd549e437ddd7ca0457a7ab79917a5f`. Its original GPL-3.0-or-later license header is retained.

- Upstream Git blob: `4c6fd0dc792e492a6b5f29357ff2750616659987`
- Source SHA-256: `1028a5adaecfca17d7e3f42aa349ae24f9aa1ec490d736e58e5e3fe97920271e`
- [Official source](https://github.com/VolmitSoftware/VolmLib/blob/0eb8a9282cd549e437ddd7ca0457a7ab79917a5f/shared/src/main/java/art/arcane/volmlib/util/mantle/io/IOWorkerSupport.java)

The Gradle integration excludes this one class from the compatible pinned dependency's source set and compiles this official replacement. This supplies upstream Windows directory-flush and atomic-replacement handling without upgrading the native terrain API or changing any generation algorithm.

The source bytes are checked against `iris.lock.json` before a run. `.gitattributes` keeps them in LF form on Windows, and the hash is recorded in the feasibility report and required during native-baseline comparison. The remaining upstream checkout stays unmodified.

## Explicit template services

`volmlib/template-services.json` describes two small overlays against the pinned `NativeStructureTerrainIntegrator` and `NativeStructurePostProcessor` classes. The original and resulting source SHA-256 values are pinned. Each textual edit must match exactly once; mismatched source, patch manifest or output fails the build. Generated sources live in the dependency's build directory; its source checkout stays untouched.

The overlays add `Supplier<StructureTemplateManager>` overloads and pass that explicit service through the existing preparation/carving calls. Existing signatures delegate with their original `world.getLevel().getStructureTemplateManager()` lookup. Terrain calculations, seed identities, erosion, source snapshots, legacy air handling and support bridging are retained. This does not remove other world-service dependencies, implement native pieces/features or establish complete parity.

All reports now record `volmlibTemplateServicesSha256`, and baseline comparison requires an exact match. Old reports without this backend input are intentionally insufficient. The legacy-reference test's Mockito dependency is confined to development probes; it is not a production world adapter.
