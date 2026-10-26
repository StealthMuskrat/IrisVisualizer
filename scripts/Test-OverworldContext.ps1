param([string]$WorkDirectory = (Join-Path $PSScriptRoot '../.work'), [switch]$StructurePlanning, [switch]$StructureBlocks)
. (Join-Path $PSScriptRoot 'Runtime.ps1')
$java = Get-Java25
$env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = (Resolve-Path -LiteralPath $WorkDirectory).Path
$lockPath = Join-Path $root 'iris.lock.json'
$lock = Get-Content -Raw -LiteralPath $lockPath | ConvertFrom-Json
foreach ($name in @('iris','volmlib','pack')) {
    $directory = Join-Path $work $name
    $actual = & git -C $directory rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $actual -ne $lock.$name.revision) { throw "Unpinned $name checkout. Run Prepare-Sources.ps1." }
    $dirty = & git -C $directory status --porcelain
    if ($LASTEXITCODE -ne 0 -or $dirty) { throw "Modified $name checkout; refusing a nonreproducible native audit." }
}
$compatibilityHash = (Get-FileHash -LiteralPath (Join-Path $root $lock.volmlib.windowsCompatibility.path) -Algorithm SHA256).Hash.ToLowerInvariant()
if ($compatibilityHash -ne $lock.volmlib.windowsCompatibility.sha256) { throw 'Windows compatibility source hash mismatch.' }
$reportRoot = Join-Path $root $(if ($StructureBlocks) { 'build/overworld-blocks' } elseif ($StructurePlanning) { 'build/overworld-structures' } else { 'build/overworld-context' })
if (Test-Path -LiteralPath $reportRoot) { throw "$reportRoot exists. Remove generated output explicitly before another audit." }
$null = New-Item -ItemType Directory -Force -Path $reportRoot
Copy-Item -LiteralPath $lockPath -Destination (Join-Path $reportRoot 'inputs.lock.json')
$env:IRIS_VISUALIZER_ROOT = $root
$env:IRIS_VISUALIZER_IRIS_VERSION = $lock.iris.version
$env:IRIS_VISUALIZER_IRIS_REVISION = $lock.iris.revision
$env:IRIS_VISUALIZER_VOLMLIB_REVISION = $lock.volmlib.revision
$env:IRIS_VISUALIZER_VOLMLIB_COMPATIBILITY_SHA256 = $compatibilityHash
$env:IRIS_VISUALIZER_VOLMLIB_TEMPLATE_SERVICES_SHA256 = Get-TemplateServicesHash -Root $root -Lock $lock
$env:IRIS_VISUALIZER_MINECRAFT_VERSION = $lock.minecraft
$env:IRIS_VISUALIZER_PACK_REVISION = $lock.pack.revision
$env:IRIS_VISUALIZER_PACK = Join-Path $work 'pack'
$env:IRIS_VISUALIZER_OVERWORLD_REPORT = $reportRoot
$env:IRIS_VISUALIZER_STRUCTURE_PLANNING = ($StructurePlanning.IsPresent -or $StructureBlocks.IsPresent).ToString().ToLowerInvariant()
$env:IRIS_VISUALIZER_STRUCTURE_BLOCKS = $StructureBlocks.IsPresent.ToString().ToLowerInvariant()
Invoke-Checked (Join-Path $work 'iris/gradlew.bat') @('--no-daemon', '--max-workers=4', '--console=plain',
    '-p', (Join-Path $work 'iris'), '-I', (Join-Path $root 'gradle/feasibility.init.gradle'),
    "-PlocalVolmLibDirectory=$(Join-Path $work 'volmlib')", ':adapters:bukkit:nms:v26_3_R1:visualizerOverworldContext')
$report = Get-Content -Raw -LiteralPath (Join-Path $reportRoot 'report.properties')
foreach ($expected in @('status=NATIVE_OVERWORLD_CONTEXT_AUDIT_PASSED','previewAllowed=false','sourcePackUnchanged=true','nativeRegistryContractLoaded=true')) {
    if ($report -notmatch ('(?m)^' + [regex]::Escape($expected) + '\r?$')) { throw "Missing audit result: $expected" }
}
$placements = Import-Csv -Delimiter "`t" -LiteralPath (Join-Path $reportRoot 'native-placements.tsv')
if (-not ($placements | Where-Object { $_.structureKey -eq 'minecraft:ancient_city' })) {
    throw 'Pinned Overworld audit failed to identify its native ancient-city placement.'
}
$blockers = Import-Csv -Delimiter "`t" -LiteralPath (Join-Path $reportRoot 'preview-blockers.tsv')
foreach ($code in @('NATIVE_WORLD_INCOMPLETE','NATIVE_PLACEMENT_INCOMPLETE','NATIVE_BASELINE_MISSING','MEMORY_BUDGET_UNCONFIRMED')) {
    if (-not ($blockers | Where-Object { $_.code -eq $code })) { throw "Missing preview blocker: $code" }
}
if ($StructurePlanning -or $StructureBlocks) {
    foreach ($expected in @('structurePlanningPassed=true','ancientCityFactoryComparisons=2','missingOriginHaloRejected=true',
            'ancientCityOriginReferenceRetained=true','historyDirectoryAbsent=true','workspaceDeleted=true',
            'featuresGenerated=false','nativeParity=NOT_TESTED')) {
        if ($report -notmatch ('(?m)^' + [regex]::Escape($expected) + '\r?$')) { throw "Missing structure result: $expected" }
    }
    $starts = Import-Csv -Delimiter "`t" -LiteralPath (Join-Path $reportRoot 'structure-starts.tsv')
    $cities = @($starts | Where-Object { $_.structure -eq 'minecraft:ancient_city' -and $_.configuredOwnership -eq 'true' -and [int]$_.pieces -gt 0 })
    if ($cities.Count -lt 2) { throw 'Native structure proof did not retain both configured ancient cities.' }
    Write-Host 'PASS: real Overworld native structure starts, ownership, direct factory comparison and radius-eight origin references. Block placement and native features remain unverified.'
}
if ($StructureBlocks) {
    foreach ($expected in @('structureBlockBoundaryAudited=true','structureStageComplete=false','partialBlockResultsDiscarded=true',
            'structureTerrainMissingService=ServerLevel','terrainGenerationAttempted=false',
            'structureBlockFixture=isolated-air-input-not-generated-terrain',
            'templatePreparationPassed=true','templatePreparationCases=2','templatePreparationHashesMatch=true',
            'templateStandaloneServerWorldUsed=false','templatePreparationResultsDiscarded=true')) {
        if ($report -notmatch ('(?m)^' + [regex]::Escape($expected) + '\r?$')) { throw "Missing block-boundary result: $expected" }
    }
    $boundaries = @(Import-Csv -Delimiter "`t" -LiteralPath (Join-Path $reportRoot 'structure-block-boundaries.tsv'))
    if ($boundaries.Count -ne 2 -or -not ($boundaries | Where-Object {
            $_.phase -eq 'terrain-preparation' -and $_.status -eq 'BLOCKED' -and $_.missingService -eq 'ServerLevel'
        })) { throw 'Native structure terrain dependency was not captured.' }
    Write-Host 'PASS: real-pack structure block service boundary audited. Isolated partial results are discarded; this does not establish a complete structure stage.'
    $templates = @(Import-Csv -Delimiter "`t" -LiteralPath (Join-Path $reportRoot 'template-preparation.tsv'))
    if ($templates.Count -ne 2 -or ($templates | Where-Object {
            [int]$_.carvedBlocks -le 0 -or [int]$_.templateRequests -le 0 -or $_.standaloneSha256 -ne $_.legacySha256
        })) { throw 'Explicit native template preparation did not match both legacy reference cases.' }
    Write-Host 'PASS: standalone native template preparation carved solid fixture blocks identically to the legacy lookup path at negative and positive origins. Complete structure placement remains blocked.'
} elseif ($report -notmatch '(?m)^structureBlocksPlaced=false\r?$' -and $StructurePlanning) {
    throw 'Planning-only proof unexpectedly placed structure blocks.'
}
Write-Host 'PASS: native Overworld context audit and readiness gate. Complete native preview remains blocked; parity is not claimed.'
