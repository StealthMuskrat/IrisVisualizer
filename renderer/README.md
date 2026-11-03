# Renderer foundation

Java 25 library, separate from the editor and generation engine. UI implementation and automated validation finished before this stage; see [UI-VALIDATION.md](../docs/UI-VALIDATION.md).

The renderer currently implements read-only capture of a **user-selected** client JAR/ZIP, an immutable vanilla asset catalog, and bounded blockstate/model resolution for the pinned 26.3 profile. It does not open a preview, download assets, execute client classes, modify a pack or change generation readiness.

Build sources with a Java 25 JDK:

```powershell
gradle -p renderer classes
```

The module has no external dependencies. Its twelve main Java sources compile with `javac --release 25`. Renderer tests have not been executed; stage-wide testing remains deferred until renderer implementation is complete.

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

## Blockstate and model plans

`BlockModels.resolve(assets, nativeState, cancelled)` is a worker-side API. `RenderBlockState` requires the original native state ID/text, exact block ID/properties and complete native property-value domains. The resolver does not query a replacement registry, invent missing properties or rewrite the engine's state representation. The returned `ModelPlan.state()` retains that exact input object.

The resolver handles literal variants and multipart predicates, including nested `OR`/`AND` and negated/alternative property values. All selectors are checked against the supplied native domains. Weighted choices remain ordered alternatives; each output part retains its original dispatch pointer/case index. `Part.choose(ticket)` only consumes an externally supplied valid weight ticket. It implements no PRNG or native random-seeding replacement.

Model parents are traversed with cycle/depth checks. Ancestor geometry uses descendant texture overrides and inherited ambient-occlusion settings. Each resolved element retains its declaring model/pointer. Texture-variable chains resolve against the complete inherited binding map, with cycle/missing-variable checks. String sprite declarations and the newer `sprite`/`force_translucent` material objects are supported. A referenced direct PNG must be captured; generated/atlas-only sprites remain unsupported.

The plans retain X/Y/Z blockstate quarter turns and UV lock; element bounds and legacy or multi-axis rotations; optional face UVs/quarter turns/cull directions/tint indices; light emission and `shade_direction_override`. They are **unbaked** descriptions: absent UVs, transforms/rescale, culling, alpha/render-pass classification, tinting and lighting are not yet turned into vertices or pixels. Native render-shape, fluids and special block renderers still need integration.

Missing, malformed, unsupported or over-budget assets produce a structured placeholder problem, retaining the original native state and publishing no partial multipart geometry. Cancellation propagates separately. Ambiguous variants, built-in models, unknown fields and explicit empty child geometry with a parent are guarded; the latter remains unresolved until native inheritance behavior is verified. No global cross-snapshot cache is used.

Default per-resolution limits are 256 source assets, 8 MiB JSON, 262,144 parsed JSON nodes, parent depth 64, 4,096 declared/resolved elements, 24,576 declared/resolved faces, 4,096 dispatch cases, 1,024 choices, 16,384 texture declaration/overlay operations, and 4,096 condition nodes at depth 32. Individual model JSON is limited to 1 MiB. See [the supported format profile](FORMAT-PROFILE.md) for source references and boundaries. These limits do not confirm the combined native/world/mesh budget.

## Still to implement

Oriented geometry/UV baking, native weighted-choice seeding, tint/cutout/transparency/fluids and special render shapes, texture decoding/animation/atlas management, mesh and GPU lifetime/budgets, and map/3D presentation remain pending. Missing or unsupported visual models must later draw explicit visual placeholders while retaining the original generated block ID/state. There is currently no editor integration or ready-for-preview flag in this module.

End-of-stage validation must cover normal and ZIP64 archives, duplicated/truncated/corrupt directory data, malformed/duplicate-key UTF-8 JSON, version mismatch and incomplete archives, exact resource identities, size/CRC and budget failures, cancellation/source changes/private cleanup, and captured stream/copy immutability (including `transferTo` with an output stream that modifies its input). Model contracts must cover exact native domains, selector ambiguity/negation/nesting, weighted boundaries and original case indices, parent/texture cycles and overlays, geometry provenance and stable face order, modern material/rotation/shading declarations, every budget boundary, cancellation and whole-result placeholders. Geometry tests must compare the renderer's inputs with the engine's actual block/state/biome data. Visible Windows/GPU verification remains separate from headless contracts.
