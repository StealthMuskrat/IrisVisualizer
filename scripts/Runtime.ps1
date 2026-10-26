Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Java25 {
    if (-not [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::Windows)) {
        throw 'Iris Visualizer supports Windows x64 only.'
    }
    if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne [System.Runtime.InteropServices.Architecture]::X64) {
        throw 'Iris Visualizer requires Windows x64.'
    }
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else {
        $command = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($null -eq $command) { throw 'Install Java 25 x64 and set JAVA_HOME or add java.exe to PATH. No runtime is bundled or installed automatically.' }
        $command.Source
    }
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw "Java was not found at $java. Install Java 25 x64 and correct JAVA_HOME." }
    $settings = (& $java -XshowSettings:properties -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'Java could not start. Install a working Java 25 x64 runtime.' }
    if ($settings -notmatch '(?m)^\s*java\.specification\.version\s*=\s*25\s*$' -or
        $settings -notmatch '(?m)^\s*os\.arch\s*=\s*(amd64|x86_64)\s*$') {
        throw 'Iris Visualizer requires Java 25 x64. Set JAVA_HOME to that installation. No runtime is bundled or installed automatically.'
    }
    return (Resolve-Path -LiteralPath $java).Path
}

function Invoke-Checked {
    param([Parameter(Mandatory)][string]$Program, [Parameter(Mandatory)][string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

function Get-TemplateServicesHash {
    param([string]$Root, $Lock)
    $hash = (Get-FileHash -LiteralPath (Join-Path $Root $Lock.volmlib.templateServices.path) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $Lock.volmlib.templateServices.sha256) { throw 'Native template service manifest hash mismatch.' }
    return $hash
}
