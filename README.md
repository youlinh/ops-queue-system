# 运维叫号台

面向版本发布和数据维护的取号、自动分派与任务执行系统。开发人员提交任务，
系统按操作开始日期匹配二三线值班人员；运维管理员叫号、执行并填写实际耗时，
运维组长维护账号、排班、可用性、重分配、统计和审计日志。

## 快速启动

环境要求：Docker Desktop（支持 Compose v2）。

```powershell
Copy-Item .env.example .env
```

编辑 `.env`，替换每一个 `replace-*` 示例值。`JWT_SIGNING_KEY` 至少使用
32 个随机字符，生产环境通过 HTTPS 访问时将 `JWT_COOKIE_SECURE` 设为
`true`。

```powershell
docker compose up -d --build
docker compose ps
Invoke-WebRequest http://127.0.0.1:8080/healthz
```

浏览器访问 `http://127.0.0.1:8080`。默认只向宿主机暴露 Web 端口，
MySQL 和 API 仅在 Compose 网络内可达。

## 首次配置

1. 使用 `.env` 中 `BOOTSTRAP_LEADER_USERNAME` 和
   `BOOTSTRAP_LEADER_PASSWORD` 登录。
2. 按页面要求立即修改初始密码。
3. 在“人员与可用性”创建开发人员、运维管理员和其他固定组长账号。
4. 在“值班管理”下载标准模板，至少导入并确认今天、明天的二三线值班表。
5. 开发人员从“我要取号”提交版本发布或数据维护任务。

本地账号密码只由运维组长管理；系统允许多名固定组长，均拥有完整管理权限。

## 核心分派规则

- 匹配“操作开始时间所在日期”的值班表。
- 白天 08:30–17:30 始终优先当天二线；二线不可参与时转三线，三线也
  不可参与才进入公平分配。
- 17:30 后二线先接 3 个，再由三线接 3 个；两者均达到 3 个后进入
  公平分配。
- 21:00 后取当天任务，优先当天二线。
- 公平分配排除次日值班人员，先按当天已分配数量均衡，再按当月已完成任务
  实际耗时从少到多分配。
- 当前负责人可以直接转交并立即生效；组长可手动调整，或对不可参与人员的
  待执行任务发起重新分配。执行中任务保留原负责人并提示人工调整。
- 叫号后，取号的开发人员会在页面右下角收到提醒（约半分钟内，无需刷新）。

## 日常运维

查看状态与日志：

```powershell
docker compose ps
docker compose logs --tail 200 api
docker compose logs --tail 200 db
docker compose logs --tail 200 web
```

备份：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/backup.ps1 `
  -OutputDirectory C:\opsqueue-backups `
  -EnvFile .env
```

受保护的恢复：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/restore.ps1 `
  -InputFile C:\opsqueue-backups\ops-queue-20260726-120000.sql `
  -ConfirmDatabaseName ops_queue `
  -EnvFile .env
```

恢复会写入当前 `.env` 指向的数据库。正式恢复前停止业务写入、再次备份，
并优先在独立 Compose 项目和新卷上演练。完整说明见
[`docs/operations.md`](docs/operations.md)。

## 开发与验收

开发环境还需 Java 21、Maven、Node.js/Corepack。首次运行浏览器验收前安装
项目锁定版本的 Chromium：

```powershell
corepack pnpm --dir frontend install --frozen-lockfile
corepack pnpm --dir frontend exec playwright install chromium
```

一键完整验证：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1
```

脚本依次执行后端测试、前端单元测试、类型检查、生产构建、Compose 配置与
镜像构建，并创建独立的 `opsqueue-e2e` 数据卷执行真实浏览器验收。该测试卷
会在开始前和结束后删除，不会操作默认 `opsqueue` 数据卷。

保留数据停止系统：

```powershell
docker compose down
```

不要在没有确认备份时执行 `docker compose down -v`，因为 `-v` 会删除
MySQL 命名卷。
