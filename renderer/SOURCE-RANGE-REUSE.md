# Source-backed world picking bounds

Renderer implementation with compilation only. Both compilation sets pass with Java 25 and LWJGL 3.4.1: 144 full renderer/validation files and 106 dependency-free files. Three additional CPU groups bring the prepared total to 129: 96 CPU/context-free and 33 GPU. Existing world/marker GPU groups also prepare storage-mode comparisons. All assertions remain unexecuted until renderer implementation is complete. Native/GPU correctness, timings, total application memory and final editor integration remain pending; unsupported previews stay disabled.

## Reuse complete immutable metadata

World sections already retain original face and diagnostic marker leaf bounds and balanced internal unions for drawing. `WorldPickIndex` now reads that metadata through its independently retained complete-scene lease. Default preparation no longer copies those leaves, rebuilds their internal trees, or allocates per-family section prefixes.

The section hierarchy remains index-owned and independently built. Original meshes, native state/biome identities, cell/face/marker indices, UVs, alpha policy, clipping and detached nearest-hit tie rules are unchanged. No generated data, configured height, primitive budget or readiness gate is reduced or bypassed.

`WorldPickIndex.RangeStorage` makes the choice explicit through `Limits.rangeStorage` and `withRangeStorage`:

| Storage | Bounds used by group traversal |
| --- | --- |
| `SOURCE` (default) | Existing immutable section leaf/internal bounds, addressed by local group/node indices |
| `COPY` | Index-owned flattened leaf bounds, independently rebuilt internal unions, and section-prefix arrays |

Earlier two/three-argument Limits constructors remain available and select SOURCE. `withRangeHierarchy` preserves the selected storage, and `withRangeStorage` preserves hierarchy and byte ceilings. Null storage is rejected during limits construction. `COPY` retains the earlier complete allocation/build path for explicit comparisons; it does not silently fall back to SOURCE if its allowance is insufficient.

`rangeHierarchy` remains a build/traversal capability independent of storage. Disabling it prevents hierarchical queries for that index; SOURCE then reads source leaves directly and COPY omits its internal arrays. Query options still provide independent linear-group and exhaustive primitive paths. Their node/face/marker/range caps and failure counters retain their previous semantics.

## Local and flattened addresses

SOURCE traversal starts each reached section's family at local group zero and its internal root at local node zero. COPY uses the family's flattened section prefixes. Both retain the same balanced split and original interval coverage. Primitive emission converts either address back to the original section-local face/marker interval before testing actual geometry.

Leaf/internal bounds enter the same conservative slab, clip and nearest-distance predicates with the same arithmetic order. Source and copied arrays contain the same floats; COPY builds its unions independently from prepared leaves. Source metadata is exposed only through immutable component readers, without exporting or retaining mutable array aliases.

Preparation still visits each section on the CPU worker and verifies drawable/family counts against the complete scene receipt. It now also checks actual face/marker internal-node totals against groups minus family-bearing sections before any index arrays allocate. SOURCE skips all range-copy/tree-construction loops after this check. COPY retains their cancellation checks and independent union construction. Section hierarchy construction, source lifetime and final publication checks remain in both paths.

## Exact allocation and admission

Let S be all sections, D drawable sections, F/M face/marker groups, and Sf/Sm sections containing each family. Define N = 2D − 1 when D > 0, otherwise zero. When hierarchy is enabled, If = F − Sf and Im = M − Sm; otherwise both are zero.

| Primitive payload | SOURCE | COPY |
| --- | --- | --- |
| Section hierarchy | 60 × N | 60 × N |
| Range leaves and internal bounds | 0 additional | 24 × (F + M + If + Im) |
| Family leaf prefixes | 0 | 4 × (S + 1) for each present leaf family |
| Family internal prefixes | 0 | 4 × (S + 1) for each nonzero internal family |
| Build-only drawable IDs/cursor | 4 × D + (D > 0 ? 4 : 0) | Same |

The entire complete-source payload remains charged, including its retained leaf/internal bounds. SOURCE avoids duplicate index arrays; it does not release or discount source ownership. Logical primitive totals retain existing exclusions for JVM headers/objects, recursion, native/driver memory and unrelated application owners.

A one-section scene owns a 60-byte section node plus eight build-only bytes in SOURCE, regardless of how many admitted face/marker groups it contains. A single face group in COPY retains its earlier 92-byte index plus eight scratch bytes. A wholly empty scene needs no index primitive arrays in either mode and retains its complete source only.

The same selected-mode sizing helpers drive metadata-only worker submission and actual preparation. The context/UI handoff still scans no sections, vertices, faces or markers. The worker verifies the actual selected peak against its reservation, index ceiling and combined source allowance before allocation. Direct/shared preparation uses those same checks. Existing 16 MiB index, 512 MiB combined and worker/shared limits remain unchanged.

Completed indices shrink to complete source plus actual retained index arrays after temporary build owners unwind. Pointer-query reservations inherit that smaller index payload while still admitting the full source and exact displayed atlas. COPY admission deficits remain explicit errors; pending/READY replacement rejection preserves the prior accepted revision and output.

## Ownership through close and queries

Reuse is covered by the index's existing independent source lease. Closing a caller's scene/frame owner, preparation worker or cache does not revoke a transferred index. Closing an index rejects new queries and cooperatively cancels active ones; source metadata and admission remain owned until the last query unwinds. Final release clears source/range references and visits source/hold cleanup under the existing error aggregation rules.

No additional source owner, per-section retained object array, GL reference or mutable native graph is introduced. SOURCE and COPY indices have independent IDs and ownership even when they share the same immutable scene. No wall-clock or total-process-memory improvement is claimed.

## Prepared deferred coverage

Three new CPU groups prepare four-section geometry/marker/mixed/empty sources with holes, shifted placements, distant anchors, clipped/boundary/randomized rays, transparent-alpha rejection, geometry-only inspection, explicit linear/exhaustive modes and identical bounded failure/work receipts. They compare SOURCE and COPY hits and counters directly and compare sufficient-budget source queries with exhaustive original primitives.

Admission cases prepare independently calculated exact retained/build totals for both storage and hierarchy choices, absent families, one-byte-short index/combined/shared ceilings, complete source charging and scratch release. Worker cases prepare exact minimal reservations, rejected copied replacements preserving READY state, pending replacement cleanup, transferred index use after caller/cache/worker closure and final source/admission release. Earlier copy-specific exact-budget/cancellation groups explicitly select COPY so that path remains covered. Existing close-during-query and cleanup-failure groups now exercise the SOURCE default.

Existing world and marker GPU groups prepare indices from separate completed-frame source leases in both modes, compare exact displayed-raster/clip hits and work receipts, and verify that SOURCE owns only the section hierarchy beyond its full source. All 129 prepared groups remain unexecuted. Production inventory remains 138 files (94 CPU, 44 GL), plus five harnesses and one fixture. No PowerShell/Gradle task or renderer/native/GPU execution ran for this batch.
