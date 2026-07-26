[CmdletBinding()]
param(
    [string]$EnvFile = ".env.e2e.example"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$resolvedEnv = (Resolve-Path -LiteralPath (Join-Path $repoRoot $EnvFile)).Path

function Read-EnvValue([string]$Name) {
    $line = Get-Content -LiteralPath $resolvedEnv -Encoding UTF8 |
        Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } |
        Select-Object -Last 1
    if (-not $line) {
        throw "$Name is missing from $resolvedEnv"
    }
    return ($line -split "=", 2)[1].Trim().Trim('"').Trim("'")
}

$webPort = Read-EnvValue "WEB_PORT"
foreach ($name in @("BOOTSTRAP_LEADER_USERNAME", "BOOTSTRAP_LEADER_PASSWORD")) {
    [Environment]::SetEnvironmentVariable($name, (Read-EnvValue $name), "Process")
}
$env:E2E_BASE_URL = "http://127.0.0.1:$webPort"

Push-Location $repoRoot
try {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $containerIds = @(& docker compose --env-file $resolvedEnv ps -q)
        $healthy = $containerIds.Count -eq 3
        foreach ($containerId in $containerIds) {
            if ((& docker inspect --format "{{.State.Health.Status}}" $containerId).Trim() -ne "healthy") {
                $healthy = $false
            }
        }
        if ($healthy) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    if (-not $healthy) {
        & docker compose --env-file $resolvedEnv ps
        throw "The E2E Compose stack did not become healthy within 120 seconds."
    }

    & corepack pnpm --dir frontend exec tsx tests/e2e/seed.ts
    if ($LASTEXITCODE -ne 0) {
        throw "E2E seed failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
