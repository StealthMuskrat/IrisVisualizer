# Bounded diagnostic marker culling

Renderer implementation with compilation only. The full 144-file renderer/validation set and dependency-free 106-file set compile with Java 25 and LWJGL 3.4.1. Four additional prepared groups bring the total to 126: 93 CPU/context-free and 33 GPU. All remain unexecuted until renderer implementation is complete. No shaders, GL contexts, renderer harnesses, native adapters or Windows validation ran. Native/GPU correctness, timing measurements, total application memory and final editor integration remain pending; unsupported previews stay disabled.

## Original marker bounds

Diagnostic markers still represent unsupported original native cells as magenta unit wire boxes with crossed diagonals. They do not substitute another native block/state, move with model offsets, or invent geometry for an explicitly empty cell.

`WorldMesher` now emits immutable bounds for original marker groups of at most 128 instances, alongside the existing face-group metadata. Each leaf stores six section-relative floats for the complete unit boxes. Each balanced preorder internal node stores its children's exact component-wise union. Original marker order, cell provenance, state/biome identities, visual problems and full configured source extent remain intact.

For `g = ceil(markers / 128)`, source metadata costs `24 * g` leaf bytes plus `24 * max(0, g - 1)` internal bytes. Empty/geometry-only sections own no marker-bound primitive payload. The hard section ceiling of 32,768 markers permits at most 256 groups and 12,264 metadata bytes before tighter existing limits. Construction reuses the bounded tree builder and checks cancellation per internal node.

The count pass checks the complete marker metadata against mesh limits when each marker is counted, including marker-only sections with zero packed face bytes. Section output/combined planning, scene planning, cache retention and existing worker/shared/device source journals include these bytes before output allocation. Publication checks exact leaf/internal lengths and output payload. Existing ceilings remain unchanged, so a former exact-boundary request can fail explicitly. Object, array-header, stack, native and driver overhead retain their previous external accounting boundaries.

`WorldMesh.markerBatchCount/Bytes/Minimum/Maximum` and `markerBatchHierarchyCount/Bytes/Minimum/Maximum` expose immutable values and accounting. Complete-scene publication validates its marker-group receipt against the emitted arrays. `WorldPickIndex` copies the prepared leaves instead of rescanning every marker position. Picking retains independently admitted leaf/internal arrays, source ownership, exact primitive/clip/alpha policies and deterministic nearest-hit ties; both source and index payloads remain charged.

## Shared classification modes

The existing `GlCullingMode` now controls diagnostic groups as well as world face groups:

| Mode | Diagnostic marker preparation |
| --- | --- |
| `HIERARCHY` | Classify visible-section roots; accept/reject whole intervals and descend uncertain nodes |
| `LINEAR` | Independently test each reached original leaf once and cache its decision |
| `NONE` | Submit all markers after common section rejection; retain fragment clipping |

Boolean true/default selects hierarchy; false selects none. Existing typed overloads on renderer/frame/scratch preparation remain the API. Face and marker decisions use separate prefixes, masks and work counters, so one family cannot overwrite another's decisions. Sections rejected before group work need neither face nor marker classifications.

Marker leaf bounds follow the shader's `corner + cell + sectionOffset` position. Local cells/corners are bounded exact integers, so their sums match the emitted unit-box extrema before float anchor addition. Parent/leaf transforms are monotone. Conservative frustum rejection, four-ULP-plus-1e-6 clip expansion and strict unexpanded containment use the same world predicates as face groups. Whole-parent acceptance requires minimum-support containment for every frustum plane and containment in the active clip. Uncertain boundary cases descend to original leaves. These source predicates do not establish executed boundary-pixel parity.

## Original instance runs on OpenGL 3.3

The marker pass walks original groups and coalesces consecutive surviving instances. Each retained run binds its section's existing marker buffer to the owned VAO's instanced cell attribute at `firstInstance * 12` bytes, then uses ordinary `glDrawElementsInstanced`. No base-instance extension, new shader, index/instance permutation or GPU buffer is introduced.

Every submitted run sets its own offset, including a disabled-culling full-section draw starting at zero. A previous clipped draw's nonzero last-run offset therefore does not carry into later modes or images. Existing divisor/layout setup remains unchanged. Host VAO/array-buffer bindings and draw state follow the existing save/restore path; only backend-owned VAO attribute pointers are changed. Immutable completed targets and source/frame/readback owners retain their existing lifetimes.

`FrameStats` adds four fields:

| Field | Meaning |
| --- | --- |
| `markerBatchTests` | Actual marker leaf tests |
| `markerBoundsTests` | Actual internal plus leaf classifications |
| `culledMarkerBatches` | Original groups rejected, including bulk descendants |
| `culledMarkerInstances` | Original instances skipped by group rejection inside otherwise visible sections |

`markerInstances` remains submitted instances; `markerDrawCalls` counts retained contiguous runs. Rejected whole sections remain described by existing section counters. Existing face counters and triangle-index totals retain their meanings. Older ten/thirteen/sixteen/seventeen-argument constructors remain available with zero new marker metadata. Bulk rejected group counts can exceed tested leaves; marker leaf work cannot exceed total marker bound work.

## Draw scratch and ownership

Enabled marker grouping adds one byte per original marker group and, only when that family is present, one integer prefix per section plus a sentinel. This is added to the existing 1,024-byte math allowance, section offsets/visibility and face prefixes/mask. Hierarchy and linear modes admit identical scratch. None omits both group masks/prefixes. Source bounds and bounded recursive traversal supply parent decisions without per-node draw arrays or GL allocations.

Scratch is admitted before helper construction, host-state changes or target writes. Helpers unwind before the covering journal retires. A deficit preserves a previous completed target and host state; cleanup failure retains existing journal/quarantine accounting. Successful frame publication still requires completed drawing, host restoration and scratch cleanup. Mask filling and run traversal still touch original groups, and intersecting hierarchies can perform more classifications than linear traversal. No measured speedup is claimed.

## Prepared deferred coverage

Two new CPU groups prepare independent original-cell bound oracles for 0/1/127/128/129/513 markers, odd/partial groups, shifted model placements, empty/full source identities, picking-mode equivalence, exact and one-byte-short section/scene/combined budgets, late cancellation and retained/cache sharing. Prior mixed-source output assertions now include marker metadata.

One new context-free math group prepares deterministic camera/FOV/aspect/clip/rebased-anchor comparisons among hierarchy, independent leaves, linear and disabled modes. Coverage includes separate face/marker masks, reverse/forward complete lookups, contained-root acceptance, clipped subtree rejection, invalid indices, whole-section preflight and exact scratch sizes. Mixed fixtures alternate left/right groups to prepare two disjoint retained runs without changing source order.

One new GPU group prepares separate hierarchical/linear/disabled immutable images, repeated mode switching after nonzero instance offsets, disjoint original runs, exact submission/work receipts, original diagnostic picking, one-byte-short scratch admission, old-target/host-state preservation and contained-root frame comparisons. The fixture's marker ceiling was raised within its existing synthetic planning limits; production defaults were not increased. These are prepared synthetic assertions, not native compatibility evidence.

Production inventory remains 138 files: 94 CPU and 44 GL. Five harnesses plus one fixture bring the full compile set to 144 files. No PowerShell/Gradle task or contract execution ran for this batch.
