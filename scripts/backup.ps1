[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [string]$EnvFile = ".env"
)

$ErrorActionPreference = "Stop"

$resolvedEnv = (Resolve-Path -LiteralPath $EnvFile).Path
if (-not (Test-Path -LiteralPath $OutputDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
}
$resolvedOutput = (Resolve-Path -LiteralPath $OutputDirectory).Path

$containerId = (& docker compose --env-file $resolvedEnv ps -q db).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
    throw "Compose database container is not running."
}
$health = (& docker inspect --format "{{.State.Health.Status}}" $containerId).Trim()
if ($LASTEXITCODE -ne 0 -or $health -ne "healthy") {
    throw "Compose database container is not healthy (status: $health)."
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dumpFile = Join-Path $resolvedOutput "ops-queue-$timestamp.sql"
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
$writer = New-Object System.IO.StreamWriter($dumpFile, $false, $utf8WithoutBom)
try {
    & docker compose --env-file $resolvedEnv exec -T db sh -c `
        'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --hex-blob --default-character-set=utf8mb4 "$MYSQL_DATABASE"' |
        ForEach-Object { $writer.WriteLine($_) }
    $dumpExitCode = $LASTEXITCODE
}
finally {
    $writer.Dispose()
}

if ($dumpExitCode -ne 0) {
    throw "mysqldump failed with exit code $dumpExitCode. Incomplete file: $dumpFile"
}
if (-not (Test-Path -LiteralPath $dumpFile -PathType Leaf) -or
    (Get-Item -LiteralPath $dumpFile).Length -eq 0) {
    throw "Database dump is empty: $dumpFile"
}

Write-Output $dumpFile
