param([switch]$CheckRuntime)
$ErrorActionPreference = 'Stop'
try {
    if ($env:OS -ne 'Windows_NT') { throw 'Iris Visualizer supports Windows x64 only.' }
    $javaPath = $null
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin/java.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $javaPath = $candidate }
    }
    if (-not $javaPath) {
        $command = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { $javaPath = $command.Source }
    }
    if (-not $javaPath) { throw 'Java 25 x64 runtime was not found. Install it separately and set JAVA_HOME or add java.exe to PATH. No runtime is bundled or installed by this launcher.' }
    # Java writes version/settings to stderr. PS 5.1 treats those lines as error records.
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $settings = (& $javaPath -XshowSettings:properties -version 2>&1 | Out-String)
    $javaExit = $LASTEXITCODE
    $ErrorActionPreference = $oldPreference
    if ($javaExit -ne 0) { throw 'The configured Java runtime could not start. Install a working Java 25 x64 runtime.' }
    if ($settings -notmatch '(?m)^\s*java\.specification\.version\s*=\s*25\s*$' -or
        $settings -notmatch '(?m)^\s*os\.arch\s*=\s*amd64\s*$') {
        throw 'Iris Visualizer requires Java 25 x64 (amd64). Select that runtime using JAVA_HOME or PATH.'
    }
    $library = Join-Path $PSScriptRoot 'lib'
    if (-not (Test-Path -LiteralPath $library -PathType Container) -or
        -not (Get-ChildItem -LiteralPath $library -Filter '*.jar' -File)) { throw 'Package libraries are missing. Extract the entire Iris Visualizer ZIP before launching.' }
    if ($CheckRuntime) { Write-Output 'PASS: Java 25 x64 runtime and package libraries found.'; exit 0 }
    & $javaPath '-cp' (Join-Path $library '*') 'dev.irisvisualizer.editor.EditorApp'
    exit $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
