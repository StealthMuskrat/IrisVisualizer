# Renderer foundation

Java 25 library, separate from the editor and generation engine. UI implementation and automated validation finished before this stage; see [UI-VALIDATION.md](../docs/UI-VALIDATION.md).

This batch implements read-only capture of a **user-selected** client JAR/ZIP and an immutable vanilla asset catalog. It does not open a preview, download assets, execute client classes, modify a pack or change generation readiness.

Build sources with a Java 25 JDK:

```powershell
gradle -p renderer classes
```

The module has no external dependencies. Its five main Java sources compile with `javac --release 25`. Renderer tests have not been executed; stage-wide testing remains deferred until renderer implementation is complete.

## Capturing client assets

`ClientAssets.load(selectedArchive, pinnedMinecraftVersion, cancelled)` is a worker-side API. The caller must supply the Minecraft target from `iris.lock.json`, not infer it from a pack field, archive filename or latest installed release. No installation scan or automatic download occurs.

The reader creates a bounded private temporary capture, computes its SHA-256, and rejects source size/identity/modified-time changes during capture. ZIP metadata is preflighted **before** `ZipFile` creates its index: classic/ZIP64 end records, single-disk consistency, central-directory byte/count/name bounds and actual central entry counts. Selected assets are copied with exact declared-size and CRC checks. Only the private capture is removed; selected archives remain read-only. Cancellation is cooperative through copying, directory scans, asset reads and version parsing.

A strict, bounded UTF-8 `version.json` with an exact matching string `id` is required. Missing/mismatched/ambiguous metadata and server or incomplete archives fail explicitly. This establishes the archive's declared version, not Mojang authenticity or complete visual-model compatibility.

The catalog keeps canonical lowercase `minecraft` identities for:

- `assets/minecraft/blockstates/*.json`
- `assets/minecraft/models/*.json`
- `assets/minecraft/textures/*.png`
- Texture animation metadata, `*.png.mcmeta`

Other entries are counted as ignored and are never executed. Paths are not extracted or normalized into another resource. Duplicate selected identities are rejected. Model/texture paths retain relative subfolders. Each asset has its own SHA-256 and private captured bytes; public streams and copies cannot hand a caller the catalog's backing arrays. Later source edits/deletion do not change a completed snapshot.

Default limits are 256 MiB archive bytes, 256 MiB captured asset bytes, 16 MiB per asset, 250,000 archive entries, 16 Mi UTF-16 entry-name characters and 32 MiB central-directory metadata. Version metadata is separately bounded to 64 KiB, 4,096 JSON nodes, depth 32 and 16 Ki decoded string characters. These are source-capture limits, **not** confirmation of combined generation, image, atlas, native or mesh budgets.

## Still to implement

Blockstate variants/multipart matching, model inheritance and parent/texture-variable resolution, oriented geometry and UVs, tint/cutout/transparency/fluids, texture decoding/animation/atlas management, mesh and GPU lifetime/budgets, and map/3D presentation remain pending. Missing or unsupported visual models must later produce explicit visual placeholders while retaining the original generated block ID/state. There is currently no editor integration or ready-for-preview flag in this module.

End-of-stage validation must cover normal and ZIP64 archives, duplicated/truncated/corrupt directory data, malformed/duplicate-key UTF-8 JSON, version mismatch and incomplete archives, exact resource identities, size/CRC and budget failures, cancellation/source changes/private cleanup, and captured stream/copy immutability (including `transferTo` with an output stream that modifies its input). Geometry tests must compare the renderer's inputs with the engine's actual block/state/biome data. Visible Windows/GPU verification remains separate from headless contracts.
