# Windows storage compatibility

The filesystem helper in `volmlib/src/main/java/` is an unchanged copy of upstream VolmLib's `IOWorkerSupport.java` from commit `0eb8a9282cd549e437ddd7ca0457a7ab79917a5f`. Its original GPL-3.0-or-later license header is retained.

- Upstream Git blob: `4c6fd0dc792e492a6b5f29357ff2750616659987`
- Source SHA-256: `1028a5adaecfca17d7e3f42aa349ae24f9aa1ec490d736e58e5e3fe97920271e`
- [Official source](https://github.com/VolmitSoftware/VolmLib/blob/0eb8a9282cd549e437ddd7ca0457a7ab79917a5f/shared/src/main/java/art/arcane/volmlib/util/mantle/io/IOWorkerSupport.java)

The Gradle integration excludes this one class from the compatible pinned dependency's source set and compiles this official replacement. This supplies upstream Windows directory-flush and atomic-replacement handling without upgrading the native terrain API or changing any generation algorithm.

The source bytes are checked against `iris.lock.json` before a run. `.gitattributes` keeps them in LF form on Windows, and the hash is recorded in the feasibility report and required during native-baseline comparison. The remaining upstream checkout stays unmodified.
