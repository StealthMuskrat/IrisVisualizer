param([switch]$Execute, [string]$Gradle = 'gradle')
. (Join-Path $PSScriptRoot 'Runtime.ps1')
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$module = Join-Path $root 'renderer-gl'
$report = Join-Path $module 'build/validation/gpu-contracts.properties'
# Remove earlier success evidence even for a compile-only attempt or failed compilation.
if (Test-Path -LiteralPath $report) { Remove-Item -LiteralPath $report -Force }
$java = Get-Java25
$javac = Join-Path (Split-Path $java -Parent) 'javac.exe'
if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { throw 'A complete Java 25 x64 JDK is required.' }
$command = Get-Command $Gradle -ErrorAction Stop
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
    Invoke-Checked -Program $command.Source -Arguments @('--no-daemon', '--project-dir', $module, 'clean', 'compileTestJava')
    Write-Host 'Compiled renderer/OpenGL sources and GPU contracts. No GLFW context or renderer contract was executed.'
    if ($Execute) {
        # Explicit renderer-stage validation only; never called by a default build or CI workflow.
        Invoke-Checked -Program $command.Source -Arguments @('--no-daemon', '--project-dir', $module, 'gpuContracts', "-PgpuReport=$report")
        if (-not (Test-Path -LiteralPath $report -PathType Leaf)) { throw 'GPU execution produced no successful validation report.' }
        Write-Host "GPU validation report: $report"
    }
} finally {
    $env:JAVA_HOME = $previousJava
}
