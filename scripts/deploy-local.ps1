[CmdletBinding()]
param(
    [string]$EnvFile = ".env",
    [switch]$KeepData
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Desktop 未运行。请先启动 Docker Desktop，等它就绪后重试。"
    }
    if (-not (Test-Path -LiteralPath $EnvFile)) {
        throw "$EnvFile 不存在。项目根目录应有 .env（Claude 已生成过一份）。"
    }

    if (-not $KeepData) {
        Write-Host "==> [1/4] 清理旧栈与旧数据卷（项目 opsqueue）"
        & docker compose -p opsqueue down -v --remove-orphans
        if ($LASTEXITCODE -ne 0) {
            Write-Host "    （没有旧栈可清理，继续）"
        }
    } else {
        Write-Host "==> [1/4] 保留数据模式：跳过清理"
    }

    Write-Host "==> [2/4] 构建并启动全量服务（首次构建约 3-8 分钟，请耐心等待）"
    & docker compose --env-file $EnvFile up -d --build
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose up 失败（退出码 $LASTEXITCODE）。请把上方错误输出发给 Claude。"
    }

    Write-Host "==> [3/4] 等待 db / api / web 通过健康检查（最长 5 分钟）"
    $deadline = (Get-Date).AddSeconds(300)
    $healthy = $false
    do {
        Start-Sleep -Seconds 6
        $ids = @(& docker compose --env-file $EnvFile ps -q)
        $healthy = ($ids.Count -eq 3)
        foreach ($id in $ids) {
            $status = (& docker inspect --format "{{.State.Health.Status}}" $id).Trim()
            if ($status -ne "healthy") { $healthy = $false }
        }
        Write-Host "    等待中… ($(@($ids).Count) 个容器已创建)"
    } while (-not $healthy -and (Get-Date) -lt $deadline)

    if (-not $healthy) {
        & docker compose --env-file $EnvFile ps
        throw "容器在 5 分钟内未全部变为 healthy。请运行 docker compose logs --tail 100 api 并把输出发给 Claude。"
    }

    Write-Host "==> [4/4] 设置 leader 密码（免去首次登录强制改密）"
    & powershell -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $repoRoot "scripts\set-leader-password.ps1") -EnvFile $EnvFile

    $health = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 http://127.0.0.1:8080/healthz
    Write-Host ""
    Write-Host "=============================================="
    Write-Host " 部署完成，web 自检: $($health.StatusCode) $($health.Content.Trim())"
    Write-Host " 访问地址: http://127.0.0.1:8080"
    Write-Host " 账号: leader   密码: 1qaz2wsx3edc"
    Write-Host " 下一步: 值班管理里导入今明两天值班表，再创建开发/运维账号"
    Write-Host "=============================================="
}
finally {
    Pop-Location
}
