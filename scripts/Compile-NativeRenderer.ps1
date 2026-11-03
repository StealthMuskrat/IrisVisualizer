param([string]$WorkDirectory = (Join-Path $PSScriptRoot '../.work'))
. (Join-Path $PSScriptRoot 'Runtime.ps1')
$java = Get-Java25
$env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = (Resolve-Path -LiteralPath $WorkDirectory).Path
$lock = Get-Content -Raw -LiteralPath (Join-Path $root 'iris.lock.json') | ConvertFrom-Json
foreach ($name in @('iris','volmlib')) {
    $directory = Join-Path $work $name
    $actual = & git -C $directory rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $actual -ne $lock.$name.revision) { throw "Unpinned $name checkout. Run Prepare-Sources.ps1." }
    $dirty = & git -C $directory status --porcelain
    if ($LASTEXITCODE -ne 0 -or $dirty) { throw "Modified $name checkout; refusing a nonreproducible native compilation." }
}
$compatibility = (Get-FileHash -LiteralPath (Join-Path $root $lock.volmlib.windowsCompatibility.path) -Algorithm SHA256).Hash.ToLowerInvariant()
if ($compatibility -ne $lock.volmlib.windowsCompatibility.sha256) { throw 'Windows compatibility source hash mismatch.' }
$env:IRIS_VISUALIZER_ROOT = $root
$arguments = @('--no-daemon', '--max-workers=4', '--console=plain',
    '-p', (Join-Path $work 'iris'), '-I', (Join-Path $root 'gradle/feasibility.init.gradle'),
    '-I', (Join-Path $root 'gradle/renderer-native.init.gradle'),
    "-PlocalVolmLibDirectory=$(Join-Path $work 'volmlib')",
    ':adapters:bukkit:nms:v26_3_R1:compileOfflineJava')
Invoke-Checked (Join-Path $work 'iris/gradlew.bat') $arguments
Write-Host 'Native renderer sources compiled against the pinned official offline backend. No tests or native capture/sampling task was run.'
