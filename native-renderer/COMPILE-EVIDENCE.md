# Native compiler evidence

Implementation, 2026-11-06. The Windows compile command and Gradle hooks are source-reviewed only in this workspace. PowerShell, Gradle and the genuine Windows native dependency classpath are unavailable here. No success receipt has been generated, and no native compilation, renderer contract or native/GPU capture is claimed to pass.

## Command and publication

On Windows x64 with a Java 25 x64 JDK, prepare the original pinned source checkouts and run the existing command:

```powershell
./scripts/Prepare-Sources.ps1
./scripts/Compile-NativeRenderer.ps1
```

The only requested Gradle task remains `:adapters:bukkit:nms:v26_3_R1:compileOfflineJava`. No renderer test or native capture/sampling task is added. Existing official build dependencies and feasibility source overlays still apply. Probe sources and their reference-side dependencies, including Mockito, can be present in the offline compile source/classpath; no probe or mock is executed by this command. This does not package the offline backend, a client archive or native assets into the editor.

The command removes its prior `build/native-renderer-compile/receipt.json`, `pending.json` and `pending.json.tmp` before OS/JDK/source preflight. Failure to remove an owned file stops the command. Gradle writes only an intermediate `pending.json` after compilation and its own checks. PowerShell publishes `receipt.json` by a same-directory rename only after the entire Gradle invocation succeeds, upstream pins/cleanliness are checked again, and current source/control/output hashes match the intermediate evidence. Success is printed only after final cleanup completes.

Failed preflight, compilation, postflight or publication attempts clean intermediate files and invalidate the final receipt. Cleanup attempts every owned path and preserves the original failure, attaching later cleanup exceptions to its exception data. A cleanup failure after publication attempts to remove that receipt before failing. Filesystem refusal can prevent removal; such a command never reports success. Interrupted processes can leave intermediates, which are ignored and removed by the next attempt. Partial compiler output is not success evidence.

## Fresh output and input checks

Native bridge pins, manifest coverage and source hashes retain their existing checks. The Gradle hook also checks bridge files again before compilation, after initial manifest validation. Compilation requires a Java 25 compiler on Windows x64 and the exact single compile-task invocation. Incremental compilation, up-to-date reuse and build-cache restoration are disabled for this task; the wrapper additionally disables build/configuration caching for the invocation. Original upstream language/compiler flags are retained.

The actual compiler source set must include every CPU renderer and manifest-covered bridge source. The hook clears only `dev/irisvisualizer/renderer` in this task's compiler output, including its native-bridge subpackage, and verifies that removal succeeded. The destination must be inside the upstream module's build directory. The explicit cleanup targets that renderer package; upstream source checkouts, generation data and user assets remain unchanged. The compiler may rebuild its own other task outputs normally.

Every current CPU/bridge source must produce its top-level class in that owned package. All emitted top-level and nested renderer classes must have the class-file magic and Java 25 major version 69, and each required top-level class must appear in the hashed output inventory. At the current revision this covers 92 CPU production sources and sixteen bridge sources, or 108 top-level classes before nested classes are counted. These are expected inventory counts, not an executed native compile result.

Source, control, classpath, annotation-processor-path, sourcepath and compiler-setting snapshots are compared before/after compilation. Persistent input changes fail the attempt. PowerShell additionally rechecks source/control files and every emitted class hash before publication. Keep inputs frozen during the command; these checks do not create an atomic filesystem snapshot or defend against a concurrent writer changing and restoring inputs between observations.

## Receipt fields and interpretation

The format-1 `compiled-only` JSON contains compiler metadata, native source pins, the requested task, normalized output location, control/source hashes, ordered dependency fingerprints, annotation processor/source paths, compiler flags/settings, expected top-level mappings and emitted class hashes/major versions. It explicitly records `rendererContractsExecuted: false` and `nativeCaptureExecuted: false`.

Control fingerprints include the lock, bridge manifest, compile/runtime helpers, both init scripts and compatibility files. Files use streaming SHA-256. Directory classpaths hash a sorted UTF-8 JSON inventory of relative file names, byte lengths and file hashes; traversal includes hidden files and follows symbolic links, failing on unreadable/cyclic input. Classpath order is retained. Project/upstream paths use `project/`, `iris/` and `volmlib/` roots. External dependency labels use basenames plus content fingerprints instead of absolute cache paths. Compiler source files must resolve under the known source roots for postflight validation.

A receipt is historical evidence for its fingerprints, not an automatic claim about a subsequently edited workspace. Compare it with the current source/dependency/output content before treating it as current. Hashes identify bytes; they do not authenticate external downloads, sign the build, prove byte-for-byte reproducibility, execute native methods or establish runtime/pixel parity. Annotation processors and official build tools may execute as part of compilation; renderer/native sampling harnesses are outside this invocation.

## Inspection and pending validation

Current source inspection confirms exact sixteen-file bridge manifest coverage/hashes, unique owned top-level paths for all 108 production sources, and Java-25 headers for the 92 previously compiled CPU classes. No native class was inspected and no fake native classes, mock native compilation or synthetic success receipt was used.

PowerShell/Groovy parsing and real command execution remain pending on the supported environment. When available, cover wrong OS/JDK/pins, dirty source checkouts, stale/missing/locked output, changed source/control/classpath/processor inputs, missing classes, wrong bytecode targets, Gradle failure, atomic-move failure, interrupted attempts and cleanup preserving the original error. Confirm a valid run produces a complete receipt and that failures cannot be mistaken for a new successful compile. All 70 prepared renderer groups remain unexecuted under the existing renderer-stage workflow; no CI workflow was added or dispatched for this batch.

Gradle API references: [JavaCompile](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.compile.JavaCompile.html) and [CompileOptions](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.compile.CompileOptions.html).
