param([string]$WorkDirectory = (Join-Path $PSScriptRoot '../.work'))
. (Join-Path $PSScriptRoot 'Runtime.ps1')
$java = Get-Java25
$env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = (Resolve-Path -LiteralPath $WorkDirectory).Path
$lockPath = Join-Path $root 'iris.lock.json'
$lock = Get-Content -Raw -LiteralPath $lockPath | ConvertFrom-Json
foreach ($name in @('iris','volmlib')) {
    $directory = Join-Path $work $name
    $actual = & git -C $directory rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $actual -ne $lock.$name.revision) { throw "Unpinned $name checkout. Run Prepare-Sources.ps1." }
    $dirty = & git -C $directory status --porcelain
    if ($LASTEXITCODE -ne 0 -or $dirty) { throw "Modified $name checkout; refusing a nonreproducible native proof." }
}
$compatibilityHash = (Get-FileHash -LiteralPath (Join-Path $root $lock.volmlib.windowsCompatibility.path) -Algorithm SHA256).Hash.ToLowerInvariant()
if ($compatibilityHash -ne $lock.volmlib.windowsCompatibility.sha256) { throw 'Windows compatibility source hash mismatch.' }
$reportRoot = Join-Path $root 'build/native-fixture'
if (Test-Path -LiteralPath $reportRoot) { throw 'build/native-fixture exists. Remove generated test output explicitly before another run.' }
$null = New-Item -ItemType Directory -Force -Path $reportRoot
Copy-Item -LiteralPath $lockPath -Destination (Join-Path $reportRoot 'inputs.lock.json')
$env:IRIS_VISUALIZER_ROOT = $root
$env:IRIS_VISUALIZER_IRIS_VERSION = $lock.iris.version
$env:IRIS_VISUALIZER_IRIS_REVISION = $lock.iris.revision
$env:IRIS_VISUALIZER_VOLMLIB_REVISION = $lock.volmlib.revision
$env:IRIS_VISUALIZER_VOLMLIB_COMPATIBILITY_SHA256 = $compatibilityHash
$env:IRIS_VISUALIZER_VOLMLIB_TEMPLATE_SERVICES_SHA256 = Get-TemplateServicesHash -Root $root -Lock $lock
$env:IRIS_VISUALIZER_NATIVE_PIECE_ADAPTER_SHA256 = Get-NativePieceAdapterHash -Root $root -Lock $lock
$env:IRIS_VISUALIZER_NATIVE_STRUCTURE_STAGE_SHA256 = Get-NativeStructureStageHash -Root $root -Lock $lock
$env:IRIS_VISUALIZER_MINECRAFT_VERSION = $lock.minecraft
$env:IRIS_VISUALIZER_NATIVE_FIXTURE = Join-Path (Join-Path $work 'iris') $lock.nativeFixture.sourcePath
$env:IRIS_VISUALIZER_NATIVE_FIXTURE_SHA256 = $lock.nativeFixture.sha256
$arguments = @('--no-daemon', '--max-workers=4', '--console=plain',
    '-p', (Join-Path $work 'iris'), '-I', (Join-Path $root 'gradle/feasibility.init.gradle'),
    "-PlocalVolmLibDirectory=$(Join-Path $work 'volmlib')")
foreach ($run in @('first','repeat')) {
    $env:IRIS_VISUALIZER_NATIVE_REPORT = Join-Path $reportRoot $run
    # Each JavaExec invocation starts a fresh native JVM and one immutable fixture context.
    Invoke-Checked (Join-Path $work 'iris/gradlew.bat') ($arguments + @(':adapters:bukkit:nms:v26_3_R1:visualizerNativeFixture'))
    $properties = Get-Content -Raw -LiteralPath (Join-Path $env:IRIS_VISUALIZER_NATIVE_REPORT 'report.properties')
    if ($properties -notmatch '(?m)^status=NATIVE_FIXTURE_PASSED\r?$') { throw "Native $run run did not report success." }
}
$first = Join-Path $reportRoot 'first/chunks.tsv'
$repeat = Join-Path $reportRoot 'repeat/chunks.tsv'
if ((Get-Content -LiteralPath $first).Count -ne 65 -or (Get-Content -LiteralPath $repeat).Count -ne 65) {
    throw 'Native fixture output did not contain all 64 full-height chunks.'
}
if ((Get-FileHash -LiteralPath $first -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $repeat -Algorithm SHA256).Hash) {
    throw 'Native block or biome hashes changed across fresh JVM runs.'
}
'status=NATIVE_FIXTURE_REPEATABILITY_PASSED' | Set-Content -Encoding utf8 -LiteralPath (Join-Path $reportRoot 'comparison.properties')
Write-Host 'PASS: native vanilla states and 128x128 full-height fixture repeatability. Complete Overworld native parity remains untested.'
