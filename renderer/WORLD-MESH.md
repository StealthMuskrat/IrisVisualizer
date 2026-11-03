# Native-data CPU world sections

Renderer implementation, 2026-11-03. All 42 renderer sources compile with Java 25. Runtime tests remain deferred until the renderer stage is implemented. No native generated-volume comparison, GPU rendering or visible Windows validation was executed for this batch. Unsupported previews remain disabled.

## Immutable native inputs

`WorldSnapshot.capture(source, extent, states, biomes, stateIndices, biomeIndices, cancelled)` detaches a completed native volume into private arrays. `Source` retains a caller-supplied generation UUID, complete input fingerprint, backend build, Minecraft version, dimension and seed. These are descriptive receipts; they do not authenticate generation, prove parity or grant readiness. Only the pinned Minecraft profile is accepted.

The producer must finish and freeze its palettes/arrays before capture. Capture validates and copies data cooperatively; it is not a transaction over concurrently mutating engine buffers. The input fingerprint must cover the actual generation request, including pack/configuration and temporary context overrides. No generator, native registry, biome sampler or replacement PRNG is introduced here.

`Extent` is half-open, retains the entire supplied height, and uses long X/Z origins and integer Y coordinates. Array order is X fastest, then Z, then Y. Each cell has a state palette index and biome palette index. Palette indices remain distinct from native IDs. State entries preserve the exact `RenderBlockState`, including original native text and property domains; biome entries preserve native ID, original text and resource ID. Duplicate native IDs, absent palette references and mismatched array lengths fail explicitly. No palette remapping, ID substitution or Y truncation occurs.

Snapshot getters expose immutable palettes, individual lookups and detached array copies. A failed capture publishes a source/extent receipt and structured problems without partial palettes or indices. Cancellation propagates separately. Memory failure unwinds worker capture references before reporting a problem; recovery from process-wide exhaustion is not guaranteed.

## Per-cell visual choices

`WorldModels.capture(snapshot, layout, entries, cellAssignments, cancelled)` accepts one caller-supplied visual entry index for every captured cell. Different cells with the same native state can select different mesh prototypes. Position-dependent weighted/multipart selection remains the native producer's responsibility; the renderer does not choose one global mesh per state or invent random seeding.

| Entry kind | Meaning |
| --- | --- |
| MESH | A successful atlas-bound prototype retaining the exact native state |
| EMPTY | The producer explicitly declares no visual geometry for this state/choice |
| PLACEHOLDER | Unsupported visual content, with original state and structured asset causes retained |

Every entry must match an exact state in the native palette. Every cell assignment must match that cell's original state palette entry. Native IDs are not enough to hide changed properties or original state text: entry state metadata is compared against the snapshot before compact per-cell validation. Successful prototypes must share the exact atlas layout object. Empty visual entries are not inferred from block names, missing models or alpha values.

Assignments are copied into private indices. Unique retained prototype arrays are charged by object identity; repeated references share their byte charge, independently bound copies do not. Failure publishes no partial visual palette or assignments. Snapshot and atlas inputs remain available for diagnostics.

## Explicit visibility

`WorldVisibility.keepAll(snapshot, layout)` retains every source face and allocates no per-cell mask. It is the conservative option when native visibility data is unavailable.

`WorldVisibility.capture(snapshot, layout, policyLabel, hiddenDirections, cancelled)` captures one native-produced byte per cell. Bits 0 through 5 represent DOWN, UP, NORTH, SOUTH, WEST and EAST. Undefined bits fail. Masks are bound to exact snapshot and atlas objects; matching dimensions or archive hashes alone are insufficient for assembly.

A mask bit suppresses only a face carrying that declared world cull direction. Faces without `cullface` stay present. The renderer does not infer occlusion from a solid-looking texture, cube bounds, neighbor block name or missing neighbor data. The producer remains responsible for native render shapes, same-block/transparent behavior, face comparisons and any neighbor halo outside the captured volume. The policy label is provenance, not proof that those rules were verified. Unsupported visual placeholder markers are never hidden by this mask.

## Section assembly and provenance

`WorldMesher.assemble(models, visibility, requestedExtent, cancelled)` produces a complete bounded section inside the captured volume. It first counts surviving source faces and markers, then checks all output/payload limits before allocating exact-sized primitive arrays. The second pass emits geometry in Y/Z/X cell order and original prototype face order. No greedy merging, face sorting, rescaling or omitted-content fallback occurs.

The output `WorldMesh` retains an exact integer origin at the section's minimum coordinate. Float vertices contain section-relative position XYZ, unchanged normal XYZ and atlas UV. Translation uses small cell offsets rather than converting absolute world coordinates to floats, so distant X/Z origins and negative Y do not lose integer placement during assembly. Indices are remapped per surviving quad without changing its corner order. Relative coordinates are bounded to ±128, covering admitted section offsets and the existing source geometry limits.

Each cell emitting geometry or a placeholder records its original full-volume cell index, visual entry, emitted face span and optional marker. Each emitted face retains the source prototype face/index. `stateForFace`, `biomeForFace` and `positionForFace` trace picking back to the exact native snapshot; no visual classification rewrites generation data. Empty and fully hidden visual cells remain in the snapshot even though they emit no picking records.

Consecutive matching page/alpha candidates form CPU draw ranges without reordering. This is not a transparency sort or executed render-pass policy. Tint indices, light emission, AO, cull directions, source material declarations and shade metadata remain available through the prototype face; tint and lighting are not applied.

Unsupported visual cells produce explicit unit-cell `Marker` records with exact positions, original native states and asset problems. Bounds include these unit cells. A later dedicated marker draw pass must make them visible; this CPU batch does not yet display a placeholder. Markers never replace the original world state/biome data. All-culled or empty sections can have empty geometry/bounds while still retaining their requested extent and source snapshot.

`requireRaster` checks the successful atlas epoch. It does not establish native-world readiness. Requested sections do not change the volume's height or the specified 128 × 128 preview default. The later [CPU scene layer](SCENES.md) now plans full captured-volume coverage, admits a complete scene into a leased cache and rejects obsolete CPU assembly completions. Application generation scheduling and final presentation remain separate. One section result alone is not evidence that an entire world preview has been built.

## Limits and atomic results

| Resource | Default | Hard ceiling |
| --- | --- | --- |
| State / biome palette entries | 65,536 / 8,192 | Same |
| Captured voxel cells | 16,777,216 | Same |
| Native state plus biome index bytes | 256 MiB | Same |
| Visual entries / assignment index bytes | 65,536 / 64 MiB | Same |
| Unique bound prototype arrays | 64 MiB | Same |
| Native direction mask bytes | 16 MiB | Same |
| Section axis / cell count | 32 / 32,768 | Same |
| Emitted faces / placeholder markers | 1,048,576 / 32,768 | Same |
| Section packed arrays | 64 MiB | 256 MiB |
| Retained native/assignment/mask indices | 256 MiB | Same |
| Known primitive payload working budget | 512 MiB | Same |

Callers may lower limits. Face count and packed bytes apply together; the default 64 MiB packed limit is usually tighter than the face ceiling. Counts separately bound metadata. Known payload accounting includes native state/biome/assignment arrays, masks, unique bound prototypes, atlas source images/source packets/base-level page reservation and section output arrays. The atlas's prospective bound-mesh reservation is replaced by actual retained prototype bytes to avoid charging the same arrays twice.

These byte counts exclude object/string/collection overhead, captured client ZIP data, caller-owned original arrays, unrelated/obsolete scenes, additional cached sections, atlas update scratch, direct staging, GPU memory and the native generator's working set. They do not confirm the total application memory budget. Existing proposed generation/mesh budgets and preview defaults are unchanged; later integration must reserve all live caches and GPU/native work together.

Any assembly failure publishes structured world problems without partial cells, markers, faces, ranges, bounds, usage or arrays. Coordinate-specific failures retain the affected exact world position. The original immutable inputs remain intact. Cancellation is separate and allocation failure unwinds worker assembly references before reporting. There is no UI/engine dependency, preview toggle or readiness override in this module.

## Deferred stage verification

At renderer completion, validate palette order/native ID/text/property/biome preservation, duplicate and absent IDs, invalid compact indices, version mismatch, exact array lengths, post-capture input mutation and detached copies. Cover negative coordinates/Y, large long origins, extent overflow, X/Z/Y flattening boundaries and all count/byte limits. Compare captured states/biomes against the actual pinned native generation output using identical inputs.

Exercise multiple per-position native choices for the same state, mismatched assignments, explicit empty cells, failed visual models and marker provenance. Verify exact atlas/snapshot identity, no invented RNG, and proper prototype array accounting. Test six mask directions, undefined bits, absent cull labels, keep-all mode, native transparent/shape rules and neighbor halos through independently verified native masks.

Check source face/index/normal/UV order, section-relative translation at distant origins, negative heights, cell picking, biome lookup, cull-only suppression, complete range coverage, bounds including markers, all-hidden/empty geometry and native-state retention for every visual failure. Compare count and emit passes, guard every output/combined-memory threshold, and verify cancellation/failure cannot publish partial data.

Full native model/occlusion/tint/fluid handling, application world generation/scene integration, GPU marker/mesh drawing, transparency/filtering, UI result adoption and visible Windows checks remain unfinished renderer/integration work. No renderer tests were executed for this implementation batch.
