param([string]$WorkDirectory = (Join-Path $PSScriptRoot '../.work'))
. (Join-Path $PSScriptRoot 'Runtime.ps1')
$null = Get-Java25
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$lock = Get-Content -Raw -LiteralPath (Join-Path $root 'iris.lock.json') | ConvertFrom-Json
$null = New-Item -ItemType Directory -Force -Path $WorkDirectory
$work = (Resolve-Path -LiteralPath $WorkDirectory).Path

foreach ($name in @('iris', 'volmlib', 'pack')) {
    $source = $lock.$name
    $directory = Join-Path $work $name
    if (-not (Test-Path -LiteralPath $directory)) {
        Invoke-Checked git @('init', $directory)
        Invoke-Checked git @('-C', $directory, 'remote', 'add', 'origin', $source.repository)
    }
    $remote = & git -C $directory remote get-url origin
    if ($LASTEXITCODE -ne 0 -or $remote -ne $source.repository) { throw "Unexpected origin in $directory" }
    $dirty = & git -C $directory status --porcelain
    if ($LASTEXITCODE -ne 0 -or $dirty) { throw "Refusing to change a modified checkout: $directory" }
    if ($name -eq 'pack') {
        # Keep read-only Git object files outside the generation input. The official probe copies input attributes.
        $metadata = Join-Path $work 'pack-repository'
        if (Test-Path -LiteralPath (Join-Path $directory '.git') -PathType Container) {
            if (Test-Path -LiteralPath $metadata) { throw "Refusing to overwrite existing checkout metadata: $metadata" }
            Invoke-Checked git @('-C', $directory, 'init', '--separate-git-dir', $metadata)
        }
        $actualMetadata = & git -C $directory rev-parse --absolute-git-dir
        if ($LASTEXITCODE -ne 0 -or [System.IO.Path]::GetFullPath($actualMetadata) -ne $metadata -or
            -not (Test-Path -LiteralPath (Join-Path $directory '.git') -PathType Leaf)) {
            throw "Pack checkout metadata must be outside the generation input at $metadata"
        }
    }
    Invoke-Checked git @('-C', $directory, 'config', 'core.autocrlf', 'false')
    Invoke-Checked git @('-C', $directory, 'fetch', '--depth=1', 'origin', $source.revision)
    Invoke-Checked git @('-C', $directory, 'checkout', '--detach', $source.revision)
    $actual = & git -C $directory rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $actual -ne $source.revision) { throw "Revision mismatch in $directory" }
}
Write-Host 'Pinned source checkouts are ready. No generation or parity result has been established.'
