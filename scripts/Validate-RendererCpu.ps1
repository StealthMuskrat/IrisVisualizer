param([switch]$Execute)
. (Join-Path $PSScriptRoot 'Runtime.ps1')
$java = Get-Java25
$javac = Join-Path (Split-Path $java -Parent) 'javac.exe'
if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { throw 'A complete Java 25 x64 JDK is required to compile renderer contracts.' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$destination = Join-Path $root 'build/renderer-cpu-validation'
$classes = Join-Path $destination 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$paths = @(
    Get-ChildItem -LiteralPath (Join-Path $root 'renderer/src/main/java') -Recurse -File -Filter '*.java'
    Get-ChildItem -LiteralPath (Join-Path $root 'renderer/src/test/java') -Recurse -File -Filter '*.java'
) | Sort-Object FullName
$sourceList = Join-Path $destination 'sources.txt'
$entries = @($paths | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
[System.IO.File]::WriteAllLines($sourceList, $entries, [System.Text.UTF8Encoding]::new($false))
Invoke-Checked -Program $javac -Arguments @('--release','25','-encoding','UTF-8','-d',$classes,"@$sourceList")
Write-Host "Compiled $($paths.Count) CPU production/contract sources. No native adapter or OpenGL dependency was used."
if ($Execute) {
    # Execute only after renderer implementation is complete, as required by AGENTS.md.
    Invoke-Checked -Program $java -Arguments @('-cp',$classes,'dev.irisvisualizer.renderer.RendererCpuContracts')
} else {
    Write-Host 'Compilation only; no renderer contract was executed. Use -Execute at renderer-stage validation.'
}
