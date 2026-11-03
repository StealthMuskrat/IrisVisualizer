# Native decisions to deterministic model inputs

`WorldModelInputs` turns explicit native state decisions into a frozen `WorldVisuals.Request` for a guarded initial model path. The native adapter `NativeModelInputs` obtains those decisions from actual native registry states. Neither path enables a preview or implements a replacement random-choice, fluid, special-renderer, tint or offset algorithm.

## Native capture and deferred CPU work

`NativeModelInputs.capture(context, assets, snapshot, tick, partialTick)` runs under the caller's borrowed, frozen native session. It checks the current healthy epoch, exact generation receipt, selected client profile and complete native height. Every original palette state must round-trip through the actual native registry with the same original serialization, ID, properties and complete domains. A new snapshot with only a matching state name cannot supply different native state metadata.

The adapter inspects native facts in this order:

| Native condition | Detached decision |
| --- | --- |
| Nonempty fluid state, including waterlogged blocks | Placeholder: native fluid visual producer required |
| Block entity | Placeholder: native special/block-entity visual producer required |
| Native position offset function | Placeholder: native per-cell offset producer required |
| Native invisible render shape, after the preceding guards | Explicit empty decision |
| Native model render shape, after the preceding guards | Inspect the exact selected archive's JSON model |
| Other render shape | Placeholder: unsupported native special render shape |

These facts come from native methods; block names are not used to infer air, emptiness, fluid contents, special rendering or offsets. The adapter verifies vanilla identity rather than replacing an external/deferred state with a vanilla base block. `hasOffsetFunction`, render-shape and other native API compatibility still require genuine pinned native compilation and runtime verification. Native server/common classes are borrowed from the pinned backend; user-selected client classes are not loaded or executed.

The returned `Capture.inputs()` is a `WorldModelInputs.Request`. It retains only the selected immutable asset catalog, detached native snapshot, frozen decision list, clock and bounded limits. No engine, registry, native state handle, callback or native session owner remains in that request. The host can release its native session guard after successful capture and enqueue the detached input. It must still reject obsolete completion tokens after later edits; capture is not permission to adopt a stale frame.

`NativeModelInputs.prepare` is a synchronous worker-side convenience that also performs JSON inspection, checking the borrowed native session again before returning. For the single-threaded renderer path, prefer capture followed by `WorldRenderWorker.ModelRequest`.

## Model inspection and placeholder boundaries

For a `MODEL` decision, `WorldModelInputs.prepare` resolves that native state against the exact selected immutable archive. It only supplies ticket zero when **every** matching part has total weight one. That is the only legal ticket for each such part; no PRNG runs and no arbitrary weighted choice is made. Even one choice with a larger declared weight remains guarded by this initial path.

Weighted parts produce an explicit selection problem. Tint-indexed faces produce an explicit native tint-producer problem with their original model asset/pointer. Models without verified geometric faces produce a problem instead of being inferred invisible. Malformed, missing or unsupported models carry their source problems. Other geometry/texture limitations are still checked by the later baker and texture/atlas stages. External namespaces are explicit visual problems even if a generic caller declares them empty.

The original palette state and per-cell assignments are retained. Each state contributes exactly one model, empty or placeholder choice, and every cell refers to its original state index. Native empty decisions remain distinguishable from unsupported visuals. Diagnostic markers keep the generated native ID, properties and biome data rather than substituting another block. The immutable `WorldVisuals.Request.choices()` palette exposes per-state problems without exposing mutable assignment arrays.

Resource/memory/version/atlas problems abort the complete input preparation. They are not downgraded to per-state markers to hide exceeded budgets. Cancellation propagates separately. Failure results retain detached receipts and diagnostics only; no partial assignment request escapes.

Plans are processed one state at a time, then released. Later visual baking resolves the same immutable archive/state again; this deliberately avoids retaining a second global cache of model trees or extending request lifetime with parser graphs. The second resolution cannot observe filesystem/client-archive changes because it uses the captured catalog. Inspection and bake budgets remain independently enforced.

## One CPU worker through scene completion

Existing `WorldRenderWorker.Request` still accepts ready native visual choices, including position-dependent supplied tickets and native layers. The new `WorldRenderWorker.ModelRequest` accepts a detached native decision request plus exact-snapshot `WorldVisibility.Input` instead. Both feed the same single active worker and one replaceable pending slot.

A model request enters `INSPECTING`, then `VISUALS`, `PLANNING` and `BUILDING`. There is no second inspection executor or intermediate completion queue. Already-frozen native masks can be bound to the resulting atlas. Explicit `Input.keepAll(snapshot)` remains available; this new path does not infer neighbor visibility. The complete CPU lease can be transferred to the existing `GlWorldView` endpoint with the same current revision.

A pending native-decision request charges its actual retained catalog/snapshot payload and mask; full-volume assignment arrays are not allocated while it waits. Before inspection, the worker removes only unleased old cache entries and deducts pinned old scene payload plus visibility bytes from the job allowance. The factory preflights the prospective assignment and immutable copy before allocating them. Before scene assembly, the job's retained catalog is subtracted from the scene/cache allowance; the exact native snapshot and one private choice array are shared with the scene graph.

Admission/rejection, cancellation, invalidate/take, old pinned scenes, completion ownership and cleanup follow [PIPELINE.md](PIPELINE.md). Model and ready-input jobs may replace each other, and a budget-rejected submission leaves the previous request/status/result intact. Native session ownership stays outside this CPU worker; it performs no native engine calls or generation.

## Bounds and verification status

| Resource | Default / hard bound |
| --- | --- |
| Exact native decisions / resulting choices | 65,536 |
| JSON states inspected | 1,024 |
| Selected JSON faces inspected across states | 262,144 |
| Ticket references / visual problem references | 65,536 / 65,536 |
| Per-part native ticket range admitted automatically | Total weight exactly one |
| Job CPU primitive working bound | 512 MiB |
| Active job/cache plus pending retained input | 1 GiB |

Captured asset bytes, native state/biome indices and both full-volume assignment arrays during capture are checked together. Parsed model trees, Java object headers/collections, native session memory and other external jobs remain outside primitive-payload accounting; metadata counts and existing parser/source limits still apply. Oversized areas, full heights, states, faces and references fail explicitly rather than being omitted or reduced.

All 87 independent CPU/OpenGL sources compile with Java 25 and pinned LWJGL 3.4.1. Seven native adapter sources require the genuine pinned Windows classpath; native compilation and execution remain pending. Source syntax/manifest checks do not establish native API or visual correctness. No renderer-stage tests, GL context or native visual execution ran for this batch.

At renderer completion, compare native shape/fluid/entity/offset decisions and original registry metadata with actual pinned native output. Cover unit-weight variants and multipart, larger single-choice weights, weighted alternatives, native invisible states, empty JSON model guards, tint pointers, missing/unsupported models, external/deferred states and lower limits. Verify exact full-volume choices and native IDs/biomes through markers, geometry and picking. Exercise mixed old/new request modes, rapid edits during inspection/copy/bake, pinned-cache peak admission, setup failure, rejection without mutation, invalidate/take/close races and absence of native references after capture. Native fluids/special rendering, weighted choices, offsets, tint, visibility comparison, real GPU comparison, combined application admission and editor integration remain pending; unsupported previews stay disabled.
