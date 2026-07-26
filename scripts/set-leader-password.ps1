[CmdletBinding()]
param(
    [string]$Username = "leader",
    # BCrypt hash of the new password. Default corresponds to: 1qaz2wsx3edc
    [string]$PasswordHash = '$2a$10$np1KmR/H.loQGvAQ/HK50uPhjyzkuqzycrQTAGzYeVudh0GYKPTHS',
    [string]$EnvFile = ".env"
)

$ErrorActionPreference = "Stop"

if ($Username -notmatch '^[a-z0-9._-]{1,64}$') {
    throw "Username contains unexpected characters: $Username"
}
if ($PasswordHash -notmatch '^\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$') {
    throw "PasswordHash does not look like a BCrypt hash."
}

$resolvedEnv = (Resolve-Path -LiteralPath $EnvFile).Path

$containerId = (& docker compose --env-file $resolvedEnv ps -q db).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
    throw "Compose database container is not running. Start the stack first (docker compose up -d)."
}
$health = (& docker inspect --format "{{.State.Health.Status}}" $containerId).Trim()
if ($LASTEXITCODE -ne 0 -or $health -ne "healthy") {
    throw "Compose database container is not healthy (status: $health)."
}

$sql = @"
UPDATE users
SET password_hash = '$PasswordHash',
    must_change_password = FALSE,
    version = version + 1,
    updated_at = UTC_TIMESTAMP(6)
WHERE username = '$Username';
SELECT ROW_COUNT() AS updated_rows;
SELECT username, must_change_password, updated_at
FROM users WHERE username = '$Username';
"@

$previousOutputEncoding = $OutputEncoding
try {
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $sql | & docker compose --env-file $resolvedEnv exec -T db sh -c `
        'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -t "$MYSQL_DATABASE"'
    if ($LASTEXITCODE -ne 0) {
        throw "mysql update failed with exit code $LASTEXITCODE"
    }
}
finally {
    $OutputEncoding = $previousOutputEncoding
}

Write-Output "Done. If updated_rows shows 0, the account '$Username' does not exist yet:"
Write-Output "  - Fresh install: set BOOTSTRAP_LEADER_PASSWORD in .env before first start instead."
Write-Output "Existing browser sessions stay valid up to 8h; log out/in to use the new password."
