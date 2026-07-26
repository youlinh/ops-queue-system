[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$e2eEnv = Join-Path $repoRoot ".env.e2e.example"
$exampleEnv = Join-Path $repoRoot ".env.example"

function Invoke-Checked(
    [string]$Description,
    [scriptblock]$Command
) {
    Write-Host "==> $Description"
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE."
    }
}

Push-Location $repoRoot
try {
    Invoke-Checked "Backend tests" {
        & mvn -f backend/pom.xml "-Dapi.version=1.44" test
    }
    Invoke-Checked "Frontend unit tests" {
        & corepack pnpm --dir frontend test --run
    }
    Invoke-Checked "Frontend type check" {
        & corepack pnpm --dir frontend exec vue-tsc --noEmit
    }
    Invoke-Checked "Frontend production build" {
        & corepack pnpm --dir frontend build
    }
    Invoke-Checked "Compose configuration" {
        & docker compose --env-file $exampleEnv config --quiet
    }
    Invoke-Checked "Container image build" {
        & docker compose --env-file $exampleEnv build
    }

    $project = (& docker compose --env-file $e2eEnv config --format json |
        ConvertFrom-Json).name
    if ($project -cne "opsqueue-e2e") {
        throw "Refusing to clean unexpected E2E project '$project'."
    }

    Invoke-Checked "Clean previous E2E project" {
        & docker compose --env-file $e2eEnv down -v
    }
    try {
        Invoke-Checked "Start isolated E2E stack" {
            & docker compose --env-file $e2eEnv up -d --build
        }
        Invoke-Checked "Seed deterministic E2E data" {
            & powershell -NoProfile -ExecutionPolicy Bypass `
                -File scripts/e2e-seed.ps1
        }
        $env:E2E_BASE_URL = "http://127.0.0.1:18080"
        Invoke-Checked "Playwright acceptance tests" {
            & corepack pnpm --dir frontend exec playwright test
        }
        Invoke-Checked "Git whitespace check" {
            & git diff --check
        }
    }
    finally {
        & docker compose --env-file $e2eEnv down -v
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "E2E cleanup failed; inspect project opsqueue-e2e."
        }
    }
}
finally {
    Pop-Location
}
