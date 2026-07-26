[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InputFile,
    [Parameter(Mandatory = $true)]
    [string]$ConfirmDatabaseName,
    [string]$EnvFile = ".env"
)

$ErrorActionPreference = "Stop"

$resolvedEnv = (Resolve-Path -LiteralPath $EnvFile).Path
$resolvedInput = (Resolve-Path -LiteralPath $InputFile).Path
if (-not (Test-Path -LiteralPath $resolvedInput -PathType Leaf)) {
    throw "Backup file does not exist: $resolvedInput"
}
if ((Get-Item -LiteralPath $resolvedInput).Length -eq 0) {
    throw "Backup file is empty: $resolvedInput"
}

$databaseLine = Get-Content -LiteralPath $resolvedEnv -Encoding UTF8 |
    Where-Object { $_ -match "^\s*DB_NAME\s*=" } |
    Select-Object -Last 1
if (-not $databaseLine) {
    throw "DB_NAME is missing from $resolvedEnv"
}
$databaseName = ($databaseLine -split "=", 2)[1].Trim().Trim('"').Trim("'")
if ($ConfirmDatabaseName -cne $databaseName) {
    throw "Database confirmation mismatch. Expected '$databaseName'."
}

$containerId = (& docker compose --env-file $resolvedEnv ps -q db).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
    throw "Compose database container is not running."
}
$health = (& docker inspect --format "{{.State.Health.Status}}" $containerId).Trim()
if ($LASTEXITCODE -ne 0 -or $health -ne "healthy") {
    throw "Compose database container is not healthy (status: $health)."
}

$sql = [System.IO.File]::ReadAllText(
    $resolvedInput,
    (New-Object System.Text.UTF8Encoding($false))
)
$previousOutputEncoding = $OutputEncoding
try {
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $sql | & docker compose --env-file $resolvedEnv exec -T db sh -c `
        'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$MYSQL_DATABASE"'
    if ($LASTEXITCODE -ne 0) {
        throw "mysql restore failed with exit code $LASTEXITCODE"
    }
}
finally {
    $OutputEncoding = $previousOutputEncoding
}

Write-Output "Restore completed for database '$databaseName' from '$resolvedInput'."
