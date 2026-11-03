# Complete CPU visual preparation

`WorldVisuals` connects native per-cell choices to the existing model, geometry, texture, atlas and world-assignment contracts. It performs CPU work only. Native readiness, editor presentation, GL submission and native renderer/choice production remain separate.

## Frozen native input

`WorldVisuals.Request.capture` requires a complete `WorldSnapshot` and a version-matched, explicitly selected `ClientAssetSnapshot`. The native producer supplies a choice palette and one palette index per captured cell:

| Choice | Required native decision | Result |
| --- | --- | --- |
| `ModelChoice` | Exact native state palette index and ordered weight tickets, optionally a captured native layer | Resolve JSON once for that state, bake the supplied variants, then compose the supplied layer |
| `NativeChoice` | Exact state index and captured block/fluid/special mesh | Preserve the supplied geometry and capture provenance |
| `EmptyChoice` | Explicit native empty render decision | Empty visual entry with the original state |
| `ProblemChoice` | Explicit unsupported visual problems for that state | Diagnostic entry with the original state and problems |

No air-name heuristic, replacement registry, PRNG, fluid simulation or special-renderer approximation runs. Every cell must reference a choice for its exact snapshot state. Successful native meshes/layers must preserve the selected asset epoch, exact snapshot UUID, generation receipt and native state. Position-dependent output can only be assigned to its captured cell, even if another cell has the same state. Failed native captures can carry their explicit problems without claiming successful provenance.

Capture freezes lists and copies the assignment array after count/source/working preflight. No assignment backing array is exposed. Preparation shares that private immutable array with `WorldModels`; the public `WorldModels.capture` API still copies caller-owned arrays. Reusing a frozen request for another CPU preparation does not mutate either output.

## Assembly and failures

`WorldVisuals.prepare(request, cancelled)` deduplicates equal choice records and resolves one model plan per distinct requested model state. Plans are processed state by state, then released; there is no global model/asset cache. Explicit ticket lists remain ordered and must match the resolved parts and weight ranges. Native layer composition retains JSON/native face provenance and original state identity.

`TextureBatch.prepare` decodes each referenced sprite once per batch and shares immutable source images and alpha-region scans across mesh variants. Per-mesh face/sprite bounds remain in force, along with aggregate texture byte, metadata, frame, scan and pixel-visit limits. Encoded/decoded visitation charges remain conservative when decoding or metadata fails. Exported retained RGBA counts only images reachable from successful preparations. Each mesh receives its own atomic preparation and source problems; no partial per-mesh texture bank escapes.

Missing, malformed or unsupported assets and invalid native tickets become placeholder entries for their original state. Invalid UV preparation is isolated to its mesh. A multi-frame interpolated sprite becomes an explicit animation problem for each visual using it before atlas admission; supported visuals can still complete. No frame interpolation is substituted. Resource, memory, version, atlas and stale-atlas failures abort the whole request and publish no partial output. Cancellation propagates separately.

The successful preparations share one exact atlas layout. Binding, initial animation-clock rasterization and `WorldModels` capture must all succeed before `Result` exposes either models or raster. An all-empty/placeholder world receives an explicit zero-page atlas. Failure results retain only detached receipts and diagnostics, without partial snapshots, assets, layouts or meshes. Success results retain the required native snapshot, admitted prototypes, raster and usage; they do not retain the input asset catalog, ticket palette or unrelated native layers.

The caller can pass the successful models/raster to `WorldSceneWorker.Request`, supplying explicit `WorldVisibility`. No visibility, tint, lighting, scene lease or GPU object is produced by visual preparation.

## Admission and lifetime

| Resource | Default / hard ceiling |
| --- | --- |
| Choice palette | 65,536 |
| Distinct model states / mesh choice variants | 1,024 / 1,024 |
| Ticket references / explicit problem references | 65,536 / 65,536 |
| Unique raw, intermediate and produced source packet arrays | 64 MiB |
| Combined known CPU payload during assembly | 512 MiB |
| Shared prepared faces / sprites | 262,144 / 4,096 |
| Aggregate decoded RGBA / encoded texture assets | 128 MiB / 32 MiB |
| Aggregate metadata bytes / nodes / schedule entries | 1 MiB / 65,536 / 16,384 |
| Aggregate cached UV regions / alpha pixel visits | 8,192 / 64 Mi |
| Atlas pages / page dimension | 16 / 4,096 |
| Atlas raster / prospective bound mesh arrays | 128 MiB / 64 MiB |

The top-level `Limits` can lower choice, metadata-reference, source-packet and working bounds. Existing geometry, texture, atlas and assignment ceilings still apply. Capture charges both caller input and assignment-copy bytes while copying; the frozen request subsequently charges its own single copy. Assembly charges captured asset bytes, native state/biome indices, private assignments, unique supplied/intermediate/output source packets, retained decoded images, prospective bound meshes and atlas pages together. Intermediate packets stay conservatively charged through assembly. Layout working limits subtract the request payload outside the atlas graph so atlas admission cannot omit the captured world/catalog. Duplicate images/packets are counted by identity where shared.

These are primitive-payload/count bounds, not total Java heap estimates. Lists, model trees, provenance, object headers, decoder/parser scratch, caller-owned assignment buffers after capture, other generation jobs, native engine memory, external scene/cache/picking owners and GPU storage are outside this accounting. Combined application admission remains necessary before integration. Oversized input fails explicitly; cells, configured height, variants and textures are never truncated to fit.

`WorldVisualWorker` accepts frozen requests, keeps one active and one replaceable pending job, and cooperatively cancels older work. It reserves each job's full declared working ceiling, including cancelled active work, under a default/hard 1 GiB combined worker limit. Rejected submission leaves the current revision/result intact. Accepted submission drops any worker-owned previous completion. Temporary preparation graphs unwind before releasing the active reservation. Status retains only detached receipts and diagnostics.

`take(expectedRevision)` transfers the latest successful result exactly once; a stale token cannot take it. Caller-owned results remain independent and outside worker accounting, so they require separate application admission. `invalidate()` advances the revision immediately even without a replacement request, cancels pending/active work and drops untaken output. `close()` does the same without waiting for the CPU thread. No native, UI or GL API is called. Failed jobs can be followed by a fresh request; shutdown and cancellation never adopt a partial result.

## Deferred verification

Implementation has been source-inspected and compiled with Java 25 and the pinned LWJGL classes. No renderer test or GL context has run. Stage-end validation must cover mixed JSON/native/empty/problem cells, repeated states with distinct native tickets, shared decoding/region scans, malformed/missing shared textures, UV isolation, animation guards, exact capture/position rejection, public/private assignment ownership, all-empty scenes, fatal budget failures without partial outputs, lower limits and aggregate/intermediate payload boundaries, cancellation in each phase, stale take/invalidation, pending replacement, setup failure, retry and close races. Native visual comparison, interactive Windows/GPU QA and application admission/integration remain outstanding. Unsupported previews remain disabled.
