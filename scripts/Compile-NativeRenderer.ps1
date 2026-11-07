param([string]$WorkDirectory = (Join-Path $PSScriptRoot '../.work'),
    [string]$ExpectedRevision)
. (Join-Path $PSScriptRoot 'Runtime.ps1')
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidence = Join-Path $root 'build/native-renderer-compile'
$receipt = Join-Path $evidence 'receipt.json'
$pending = Join-Path $evidence 'pending.json'
$temporary = Join-Path $evidence 'pending.json.tmp'
$classBundle = Join-Path $evidence 'classes'
# These exact files belong to this command. Failure must not expose an earlier success as this attempt.
foreach ($path in @($receipt, $pending, $temporary)) {
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
}
if (Test-Path -LiteralPath $classBundle) { Remove-Item -LiteralPath $classBundle -Recurse -Force }
$published = $false
$failure = $null
try {
    if ($ExpectedRevision -and $ExpectedRevision -notmatch '^[0-9a-f]{40}$') { throw 'ExpectedRevision must be a full lowercase project commit SHA.' }
    function Get-ProjectSnapshot {
        $revision = & git -C $root rev-parse HEAD
        if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-f]{40}$') { throw 'Cannot identify the project source revision.' }
        $tree = & git -C $root rev-parse 'HEAD^{tree}'
        if ($LASTEXITCODE -ne 0 -or $tree -notmatch '^[0-9a-f]{40}$') { throw 'Cannot identify the project source tree.' }
        $dirty = & git -C $root status --porcelain --untracked-files=normal
        if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'Commit project changes before native compilation; refusing nonreproducible source evidence.' }
        return [pscustomobject]@{ revision = $revision; tree = $tree }
    }
    $project = Get-ProjectSnapshot
    if ($ExpectedRevision -and $project.revision -ne $ExpectedRevision) { throw 'Selected project revision does not match the requested native compilation.' }
    $java = Get-Java25
    $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
    $work = (Resolve-Path -LiteralPath $WorkDirectory).Path
    $lock = Get-Content -Raw -LiteralPath (Join-Path $root 'iris.lock.json') | ConvertFrom-Json
    function Confirm-UpstreamSources {
        foreach ($name in @('iris','volmlib')) {
            $directory = Join-Path $work $name
            $actual = & git -C $directory rev-parse HEAD
            if ($LASTEXITCODE -ne 0 -or $actual -ne $lock.$name.revision) { throw "Unpinned $name checkout. Run Prepare-Sources.ps1." }
            $dirty = & git -C $directory status --porcelain
            if ($LASTEXITCODE -ne 0 -or $dirty) { throw "Modified $name checkout; refusing a nonreproducible native compilation." }
        }
    }
    function Resolve-EvidencePath([string]$Path) {
        if ($Path -notmatch '^(project|iris|volmlib)/(.+)$' -or $Path.Contains('\') -or ($Path -split '/') -contains '..') { throw "Invalid compile evidence path: $Path" }
        $base = switch ($Matches[1]) { 'project' { $root } 'iris' { Join-Path $work 'iris' } 'volmlib' { Join-Path $work 'volmlib' } }
        $resolved = [System.IO.Path]::GetFullPath((Join-Path $base $Matches[2]))
        $prefix = [System.IO.Path]::GetFullPath($base).TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
        if (-not $resolved.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Compile evidence path escaped its source root.' }
        return $resolved
    }
    function Confirm-EvidenceFile($Entry) {
        $path = Resolve-EvidencePath $Entry.path
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -ne $Entry.bytes -or
            (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Entry.sha256) { throw "Compile evidence no longer matches $($Entry.path)" }
    }
    Confirm-UpstreamSources
    $compatibility = (Get-FileHash -LiteralPath (Join-Path $root $lock.volmlib.windowsCompatibility.path) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($compatibility -ne $lock.volmlib.windowsCompatibility.sha256) { throw 'Windows compatibility source hash mismatch.' }
    $env:IRIS_VISUALIZER_ROOT = $root
    $arguments = @('--no-daemon', '--max-workers=4', '--console=plain', '--no-build-cache', '--no-configuration-cache',
        '-p', (Join-Path $work 'iris'), '-I', (Join-Path $root 'gradle/feasibility.init.gradle'),
        '-I', (Join-Path $root 'gradle/renderer-native.init.gradle'),
        "-PlocalVolmLibDirectory=$(Join-Path $work 'volmlib')",
        ':adapters:bukkit:nms:v26_3_R1:compileOfflineJava')
    Invoke-Checked (Join-Path $work 'iris/gradlew.bat') $arguments
    Confirm-UpstreamSources
    $after = Get-ProjectSnapshot
    if ($after.revision -ne $project.revision -or $after.tree -ne $project.tree) { throw 'Project source revision changed during native compilation.' }
    $result = Get-Content -Raw -LiteralPath $pending | ConvertFrom-Json
    $manifest = Get-Content -Raw -LiteralPath (Join-Path $root 'native-renderer/bridge-sources.json') | ConvertFrom-Json
    $rendererCount = @(Get-ChildItem -LiteralPath (Join-Path $root 'renderer/src/main/java') -Recurse -File -Filter '*.java').Count
    if ($result.format -ne 2 -or $result.status -ne 'compiled-only' -or $result.platform -ne 'windows-x64' -or
        $result.compiler.languageVersion -ne 25 -or $result.pins.iris -ne $lock.iris.revision -or
        $result.pins.volmlib -ne $lock.volmlib.revision -or $result.pins.minecraft -ne $lock.minecraft -or
        $result.task -ne ':adapters:bukkit:nms:v26_3_R1:compileOfflineJava' -or
        $result.project.revision -ne $project.revision -or $result.project.tree -ne $project.tree -or
        $result.taskGraph.policy -ne 'no-test-or-javaexec-tasks' -or @($result.taskGraph.tasks).Count -lt 1 -or
        $result.task -notin @($result.taskGraph.tasks.path) -or
        $result.rendererContractsExecuted -ne $false -or $result.nativeCaptureExecuted -ne $false -or
        $result.bridgeSourceCount -ne $manifest.files.Count -or $result.rendererSourceCount -ne $rendererCount -or
        $result.topClasses.Count -ne ($rendererCount + $manifest.files.Count) -or $result.classes.Count -lt $result.topClasses.Count) {
        throw 'Native compiler evidence has invalid pins, scope, counts or compiler identity.'
    }
    foreach ($entry in (@($result.controls) + @($result.sources))) { Confirm-EvidenceFile $entry }
    $destination = Resolve-EvidencePath $result.destination
    foreach ($entry in $result.classes) {
        if ($entry.path -notmatch '^dev/irisvisualizer/renderer/[A-Za-z0-9_$/]+\.class$' -or ($entry.path -split '/') -contains '..' -or $entry.major -ne 69) { throw 'Invalid emitted renderer class evidence.' }
        $file = Join-Path $destination $entry.path
        if ((Get-Item -LiteralPath $file).Length -ne $entry.bytes -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw "Emitted class changed after native compilation: $($entry.path)" }
    }
    # Export only this project's owned classes, never Minecraft/backend/dependency classpaths.
    foreach ($entry in $result.classes) {
        $file = Join-Path $destination $entry.path
        $copy = Join-Path $classBundle $entry.path
        $null = New-Item -ItemType Directory -Force -Path (Split-Path $copy -Parent)
        [System.IO.File]::Copy($file, $copy)
        if ((Get-Item -LiteralPath $copy).Length -ne $entry.bytes -or
            (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw "Exported class differs from compiler evidence: $($entry.path)" }
    }
    $after = Get-ProjectSnapshot
    if ($after.revision -ne $project.revision -or $after.tree -ne $project.tree) { throw 'Project revision changed during compiler evidence export.' }
    # Publish only after the whole build and post-compilation checks succeed; same-directory rename.
    [System.IO.File]::Move($pending, $receipt)
    $published = $true
} catch {
    $failure = $_
    throw
} finally {
    $cleanupFailure = $null
    $cleanupPaths = @($pending, $temporary)
    if (-not $published) { $cleanupPaths += @($receipt, $classBundle) }
    foreach ($path in $cleanupPaths) {
        try { if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force } }
        catch {
            if ($null -eq $cleanupFailure) { $cleanupFailure = $_ }
            else { $cleanupFailure.Exception.Data[$path] = $_.Exception }
        }
    }
    if ($null -ne $cleanupFailure) {
        if ($published) {
            foreach ($path in @($receipt, $classBundle)) {
                try { if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force } }
                catch { $cleanupFailure.Exception.Data[$path] = $_.Exception }
            }
        }
        if ($null -ne $failure) { $failure.Exception.Data['NativeCompileReceiptCleanup'] = $cleanupFailure.Exception }
        else { throw $cleanupFailure }
    }
}
Write-Host "Compiled $($result.bridgeSourceCount) native adapters and $($result.rendererSourceCount) CPU renderer sources against the pinned official offline backend."
Write-Host "Project source: $($project.revision), tree $($project.tree). Owned class files: $classBundle."
Write-Host "Compile-only evidence: $receipt. No renderer contracts or native capture/sampling task ran."
