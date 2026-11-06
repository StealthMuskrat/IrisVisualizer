# Detached visual limitation reports

Renderer implementation, 2026-11-06. Compilation and source inspection only. Renderer-stage execution, Windows native/GPU comparisons and final editor integration remain pending. Unsupported previews stay disabled.

## Full-capture scope and original identity

`WorldVisualReport.capture(scene, limits, cancelled)` borrows a complete CPU `WorldScene` synchronously on a worker. Keep its owner alive through the call. It counts every captured assignment as mesh, explicitly empty or placeholder. These are full-source assignment counts, independent of camera, clipping, frustum, texture alpha or visible GPU pixels. A mesh assignment can have no currently visible faces. A report does not diagnose generation, grant readiness or change geometry/markers.

One issue represents one exact original native state ID plus one complete `ModelPlan.Problem`: code, asset kind/ID, pointer and reason. Identical diagnostics merge across visual entries for that state; different native IDs stay separate even if their textual state or diagnostic matches. Duplicate diagnostic references within one entry count each affected cell once. Distinct issues may overlap, so summed issue counts can exceed the distinct placeholder-cell total. Unassigned palette entries contribute no rows or diagnostic references.

Rows retain the original state string and block ID, complete diagnostics, total affected cells, first/last source positions and configurable earliest examples. First/last mean the immutable source's X-fastest, then Z, then Y cell order. Examples use that same order and may be disabled without truncating counts. Exact long coordinates never pass through floating point. Output rows sort by native state ID and complete diagnostic fields, independently of palette ordering. Oversized reports fail explicitly; no diagnostic text, cell counts or issue list is silently truncated.

`WorldModels.id()` identifies each immutable assignment capture independently of its native snapshot or asset archive. A report receipt includes that ID, the snapshot ID, generation source, full extent and archive hash/version identity. `report.matches(scene)` rejects another assignment capture even if native data and assets compare equal. Rebuilding sections for the same immutable assignments can reuse the report; frame-specific visibility is outside its scope. Failed reports never match.

## Bounded work and detached data

Defaults permit 16,777,216 source cells, 4,096 issues, 65,536 raw diagnostic references from used entries, four examples per issue, 67,108,864 visits, 4 MiB of UTF-16 text, 16 MiB of retained logical payload and a 512 MiB working ceiling. Hard bounds allow 65,536 issues, 262,144 references, sixteen examples, 268,435,456 visits, 16 MiB of text and 32 MiB of retained payload. Existing source limits still apply.

The first cell pass builds per-entry counts and first/last indices. Used placeholder entries deduplicate their problems and construct bounded diagnostic mappings. A second pass collects earliest examples until all requested examples are complete. Visits cover the first census, raw used diagnostic references, example-pass cells and diagnostic references, and sort comparisons. Cancellation is checked throughout traversal, diagnostic handling, example collection, sort comparisons and before publication. Text lengths are preflighted before diagnostic hashing to reject oversized strings without first hashing their full contents.

Logical retained payload is 256 fixed bytes, 64 bytes per issue, twenty bytes per example coordinate and two bytes per retained text character. Repeated text is charged conservatively per row. First/last coordinates are included in the issue allowance. Working preflight adds the complete scene's known primitive payload, configured report payload capacity, sixteen bytes per source palette entry, four bytes per permitted diagnostic reference and 32 construction bytes per permitted issue. Object headers, collection/reference arrays, native/driver work and other jobs require separate host admission; these are bounded logical estimates, not measured heap use.

The immutable report retains compact source/asset receipts, state/diagnostic strings and value records. It retains no scene, model assignment, mesh, atlas, raster, native object, property-domain map, reader or worker graph. Source records and strings can be shared because they are immutable. Failure publishes no rows/usage and zero retained payload; cancellation escapes separately. Report failure does not alter or invalidate the original scene.

## Owned admission

`captureOwned(scene, limits, memory, cancelled)` reserves the source-plus-report peak through the optional shared coordinator before the census. A successful capture shrinks admission to detached report bytes after construction has unwound. Independent `RenderInput` owners then keep that report charge alive until final release. The caller's scene ownership is borrowed and unchanged, and the scene/cache may close while a returned report remains owned. An already tracked source can be charged conservatively twice during capture. Local/aggregate limit rejection and cancellation release the temporary hold; a reporting failure escapes as a structured `WorldCapture.ReadFailure`.

[Background reporting and completed-frame handoff](REPORT-WORKER.md) now provide active/latest-pending scheduling and exact assignment receipts without running the census on the graphics thread.

## Prepared validation

The new world group prepares mixed mesh/empty/placeholder assignments, duplicate diagnostics, merging across entries, distinct original native IDs, unused entries, exact distant coordinates, canonical order under palette reversal, immutable output and stale assignment rejection. It also prepares exact/deficient cell, issue, reference, visit, text, payload and working limits; zero/one/multiple examples; all-mesh zero-diagnostic budgets; detached ownership after source shutdown; shared peak rejection; cancellation and charge cleanup without partial output.

All 132 independent CPU/OpenGL production sources and four harnesses compile with Java 25/LWJGL (136 files); the 99-file dependency-free source set also compiles. All 68 prepared groups remain unexecuted under the current renderer workflow. Native/GPU execution, actual frame/report adoption in the editor, interactive limitations display and large-world memory/performance measurement remain pending. No native parity or user-visible preview completion is claimed.
