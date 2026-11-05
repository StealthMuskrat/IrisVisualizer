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
    # Deliberately bounded to context-free helpers; the remaining GL sources require LWJGL.
    foreach ($name in @('GlFailure','GlCamera','GlOrbitCamera','GlFrustum')) {
        Get-Item -LiteralPath (Join-Path $root "renderer-gl/src/main/java/dev/irisvisualizer/renderer/gl/$name.java")
    }
    Get-Item -LiteralPath (Join-Path $root 'renderer-gl/src/test/java/dev/irisvisualizer/renderer/gl/GlMathContracts.java')
) | Sort-Object FullName
$sourceList = Join-Path $destination 'sources.txt'
$entries = @($paths | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
[System.IO.File]::WriteAllLines($sourceList, $entries, [System.Text.UTF8Encoding]::new($false))
Invoke-Checked -Program $javac -Arguments @('--release','25','-encoding','UTF-8','-d',$classes,"@$sourceList")
Write-Host "Compiled $($paths.Count) CPU and context-free display-math production/contract sources. No native adapter or LWJGL dependency was used."
if ($Execute) {
    # Execute only after renderer implementation is complete, as required by AGENTS.md.
    foreach ($suite in @('dev.irisvisualizer.renderer.RendererCpuContracts','dev.irisvisualizer.renderer.WorldCpuContracts','dev.irisvisualizer.renderer.AnimationCpuContracts','dev.irisvisualizer.renderer.gl.GlMathContracts')) {
        Invoke-Checked -Program $java -Arguments @('-cp',$classes,$suite)
    }
} else {
    Write-Host 'Compilation only; no renderer contract was executed. Use -Execute at renderer-stage validation.'
}
