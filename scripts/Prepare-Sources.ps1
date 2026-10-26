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
    Invoke-Checked git @('-C', $directory, 'fetch', '--depth=1', 'origin', $source.revision)
    Invoke-Checked git @('-C', $directory, 'checkout', '--detach', $source.revision)
    $actual = & git -C $directory rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $actual -ne $source.revision) { throw "Revision mismatch in $directory" }
}
Write-Host 'Pinned source checkouts are ready. No generation or parity result has been established.'
