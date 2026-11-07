# Native compiler evidence

The native bridge remains uncompiled against the genuine Windows dependency classpath. This batch prepares a manual Windows compile-only workflow, format-2 source-bound evidence and a scoped class artifact. Workflow lint and Groovy syntax parsing pass; the Gradle script was not evaluated. PowerShell could not start in this host, so the PowerShell changes are source-reviewed only. No native compilation, renderer contract, capture, generation, shader or GPU execution occurred, and no success receipt was generated.

## Manual Windows workflow

[Windows native renderer compilation](https://github.com/StealthMuskrat/IrisVisualizer/actions/workflows/renderer-native-compile.yml) is declared in `.github/workflows/renderer-native-compile.yml`. It has only `workflow_dispatch`: no push, pull-request or scheduled trigger. It does not change the existing renderer-stage execution policy or enable previews.

Select the intended workflow revision on GitHub. The optional `expected_revision` input is a full lowercase project commit SHA and must match that selected revision. When omitted, the workflow uses its own `github.sha`. Checkout is fixed to that SHA with persisted credentials disabled. The job rejects a mismatch before source preparation, sets up Java 25 x64 on Windows and prepares only the two native compiler repositories.

The job calls `Compile-NativeRenderer.ps1` and uploads the receipt and this project's owned class files only after success. Artifact names include the project SHA and run attempt; retention is fourteen days. Backend/Minecraft dependency jars, original dependency classes, packs and client assets are not included. Workflow contents permission is read-only, and no application publishing or repository writes occur during the job. The workflow has not been dispatched; the current GitHub connector exposes no workflow-dispatch operation.

## Local command

From a clean committed checkout on Windows x64 with a Java 25 x64 JDK:

```powershell
./scripts/Prepare-Sources.ps1 -NativeRenderer
./scripts/Compile-NativeRenderer.ps1 -ExpectedRevision (& git rev-parse HEAD)
```

`Prepare-Sources.ps1 -NativeRenderer` fetches the existing exact Iris/VolmLib pins without fetching a pack. Omitting the switch preserves the previous three-repository preparation behavior. An existing full preparation remains valid. Compilation does not require selecting client assets or generating world data.

The only requested Gradle task remains `:adapters:bukkit:nms:v26_3_R1:compileOfflineJava`. Existing official build dependencies and feasibility source overlays still apply. Probe sources and reference-side dependencies, including Mockito, can be on the offline compiler source/classpath; no probe or mock is run as native verification. No substitute native classes or synthetic success receipt are introduced.

## Source and execution scope

The wrapper verifies the project HEAD and tree, rejects dirty/untracked project changes, checks the optional expected revision and rechecks source state after Gradle and after class export. Gradle independently records the same source revision/tree and checks them around compilation. Every current CPU renderer and bridge source must be tracked, and all project-owned compiler/control inputs must belong to the committed tree. Ignored/untracked Java inputs cannot acquire a misleading committed-source receipt.

The native bridge's exact manifest coverage, hashes and source pins retain their existing checks. Compilation requires Java 25 on Windows x64 and the exact single compile-task invocation. A task-graph guard applies to this init script's root and included builds: any Gradle `Test` or `JavaExec` task rejects the attempt before task execution. This is an explicit task-type policy, not a universal sandbox for build scripts. Ordinary compilation, annotation processors and official build tools can still execute as part of a build; renderer/native sampling harnesses are outside this command.

The pinned [Iris buildSrc configuration](https://github.com/VolmitSoftware/Iris/blob/c3359e0a16438b1bb8468901c6171dcefdf63f7f/buildSrc/build.gradle) normally makes its build-logic jar depend on tests. This init script uses that configuration's existing `irisBuildLane` opt-out only for buildSrc projects, before their build scripts are evaluated. It does not edit upstream files, disable arbitrary tests after graph construction or change root runtime/cache lane selection. The graph rejection guard remains active; an unexpected test/application dependency fails the attempt explicitly.

Incremental compilation, up-to-date reuse and build-cache restoration remain disabled for the bridge task; build/configuration caching is disabled for the invocation. Original upstream compiler flags remain. The native source checkouts must still be clean and at their pinned revisions before and after the invocation.

## Fresh output and scoped export

The command removes its prior receipt, intermediates and owned `classes/` bundle before expected-revision, OS/JDK and source preflight. Invalid expected-revision strings are checked inside the command after that cleanup. Only these fixed `build/native-renderer-compile` outputs belong to this operation; a failure to remove them stops the command.

Gradle clears only `dev/irisvisualizer/renderer` within the offline task's compiler output, including the native-bridge subpackage, and verifies that cleanup. Its output must remain under the upstream module's build directory. Every CPU/bridge source must produce its owned top-level class. Every emitted top-level/nested owned class must have valid class-file magic and Java 25 major version 69. At the current inventory this means 94 CPU sources and sixteen bridge sources: 110 expected top-level classes before nested outputs. This is an expected inventory, not an executed native compile result.

Source, control, ordered classpath, annotation processor, sourcepath and compiler-setting snapshots are compared around compilation. The wrapper rechecks source/control/output hashes, then copies only validated owned classes to `build/native-renderer-compile/classes/<class path>`. Each copied byte length/hash must match the evidence. Original backend/dependency outputs are not exported.

Gradle writes `pending.json` only after its compilation checks. The wrapper publishes `receipt.json` by same-directory rename only after the complete Gradle invocation, postflight checks and owned-class export succeed. Success is printed only after final cleanup completes. No receipt exists for this implementation batch.

Failed preflight, compilation, export or publication removes intermediates and the attempted final receipt/bundle. Cleanup visits each owned path and preserves the original error, attaching subsequent failures to its exception data. A post-publication cleanup failure attempts to invalidate both receipt and bundle before failing. Filesystem refusal can prevent removal; that command does not report success. Interrupted operations can leave untrusted intermediates, which the next attempt removes. A partial compiler directory or class bundle without a successful receipt is not success evidence.

Keep the project, upstream sources and compiler inputs frozen during compilation/export. Before/after comparisons are not an atomic filesystem snapshot and do not defend against another writer changing and restoring inputs between observations.

## Format-2 receipt and artifact

The `compiled-only` JSON retains compiler metadata, native pins, normalized destination, control/source hashes, ordered dependency fingerprints, processor/source paths, compiler settings, top-level mappings and emitted class hashes/major versions. It adds:

| Field | Meaning |
| --- | --- |
| `format: 2` | Required by the updated publisher; prior format-1 output is rejected |
| `project.revision` | Exact clean project HEAD compiled |
| `project.tree` | That commit's Git tree |
| `taskGraph.policy` | `no-test-or-javaexec-tasks` |
| `taskGraph.tasks` | Root task-graph paths and concrete types, including the compile task |
| `rendererContractsExecuted: false` | No renderer contract task is requested |
| `nativeCaptureExecuted: false` | No native capture/sampling task is requested |

Included-build graphs receive the same rejection guard; their nodes are not flattened into the root task inventory. Their compiler outputs/classpath fingerprints retain the existing evidence treatment.

Control hashes now also include source preparation and the manual workflow, alongside the lock, bridge manifest, compile/runtime helpers, both init scripts and compatibility files. Streaming file SHA-256, sorted directory inventories and source-root/dependency labels retain the existing implementation. Classpath order is preserved, and external dependency labels avoid absolute developer cache paths.

Extracted successful artifacts contain `receipt.json` and `classes/`. Match every `classes/<entry.path>` against the receipt's `classes` length/hash/major inventory. The receipt's original `destination` identifies the compiler output location, not the extracted bundle path. A receipt is historical evidence for its inputs and output bytes; compare its SHA/tree with the intended source before using it as current evidence. Hashes do not authenticate downloads, sign a build, prove byte-for-byte reproducibility or establish runtime/native/GPU parity.

## Verification and remaining work

This batch verified workflow syntax/expressions with actionlint, parsed the Gradle init script with Groovy without evaluating it, and independently checked exact sixteen-file native manifest coverage/hashes. PowerShell parser/runtime checks could not be completed because its runtime failed to initialize in this host. The genuine Windows classpath remains unavailable here. No Gradle task or workflow was run.

On the supported environment, the first real run must establish genuine compiler/API compatibility. Follow with wrong SHA/OS/JDK/pins, dirty or ignored source inputs, stale/locked outputs, forbidden task graphs, missing/wrong-major classes, changed compiler inputs, export mismatch, interrupted publication and cleanup-error cases. Confirm invalid attempts cannot masquerade as fresh compiler evidence. All 141 prepared renderer groups remain unexecuted, with native comparisons, GPU validation and final integration still pending.
