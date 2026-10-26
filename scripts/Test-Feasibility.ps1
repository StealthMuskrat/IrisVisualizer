param([string]$WorkDirectory = (Join-Path $PSScriptRoot '../.work'), [switch]$NativeCapabilities, [switch]$NativeOnly)
. (Join-Path $PSScriptRoot 'Runtime.ps1')
$java = Get-Java25
$env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = (Resolve-Path -LiteralPath $WorkDirectory).Path
$lockPath = Join-Path $root 'iris.lock.json'
$lock = Get-Content -Raw -LiteralPath $lockPath | ConvertFrom-Json
foreach ($name in @('iris','volmlib','pack')) {
    $actual = & git -C (Join-Path $work $name) rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $actual -ne $lock.$name.revision) { throw "Run Prepare-Sources.ps1: $name revision is not pinned." }
    $dirty = & git -C (Join-Path $work $name) status --porcelain
    if ($LASTEXITCODE -ne 0 -or $dirty) { throw "Dirty $name source checkout. Refusing nonreproducible results." }
}
if (Test-Path -LiteralPath (Join-Path $work 'pack/.git') -PathType Container) {
    throw 'Run Prepare-Sources.ps1 to move Git metadata outside the disposable generation input.'
}
$report = Join-Path $root 'build/feasibility'
if (Test-Path -LiteralPath $report) { throw 'build/feasibility already exists. Remove this generated test output explicitly before another run.' }
$null = New-Item -ItemType Directory -Force -Path $report
$env:IRIS_VISUALIZER_ROOT = $root
$env:IRIS_VISUALIZER_REPORT = $report
$env:IRIS_VISUALIZER_PACK = Join-Path $work 'pack'
$env:IRIS_VISUALIZER_IRIS_REVISION = $lock.iris.revision
$env:IRIS_VISUALIZER_VOLMLIB_REVISION = $lock.volmlib.revision
$env:IRIS_VISUALIZER_PACK_REVISION = $lock.pack.revision
$arguments = @('--no-daemon', '--max-workers=4', '--console=plain',
    '-p', (Join-Path $work 'iris'), '-I', (Join-Path $root 'gradle/feasibility.init.gradle'),
    "-PlocalVolmLibDirectory=$(Join-Path $work 'volmlib')")
Push-Location $root
try {
    if (-not $NativeOnly) {
        Invoke-Checked (Join-Path $work 'iris/gradlew.bat') ($arguments + @(':probe:visualizerContractTest', ':probe:visualizerFeasibility'))
    }
    if ($NativeCapabilities -or $NativeOnly) {
        Invoke-Checked (Join-Path $work 'iris/gradlew.bat') ($arguments + @(
            ':adapters:bukkit:nms:v26_3_R1:test', '--tests', 'art.arcane.iris.probe.OfflineNativeFeatureFeasibilityTest',
            '--tests', 'art.arcane.iris.probe.HeadlessNativeBootstrapTest',
            '--tests', 'art.arcane.iris.probe.HeadlessNativeStateTest'))
    }
} finally { Pop-Location }
Write-Host 'Requested checks finished. Stub results are not native parity. See docs/FEASIBILITY.md.'
