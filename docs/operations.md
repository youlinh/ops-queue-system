# 运维叫号系统部署与维护

## 首次部署

1. 将 `.env.example` 复制为 `.env`。
2. 替换所有示例密码与 `JWT_SIGNING_KEY`；JWT 密钥至少 32 个随机字符。
3. 启动系统：

```powershell
docker compose up -d --build
docker compose ps
```

只有 Web 端口会暴露到宿主机，默认访问地址为
`http://127.0.0.1:8080`。MySQL 和 API 只在 Compose 内部网络可达。

首次启动且数据库中没有启用的运维组长时，系统使用
`BOOTSTRAP_LEADER_*` 创建首名组长。初始密码登录后必须立即修改。
系统已有启用组长时，不再读取或重置该账号。

## 健康检查与日志

```powershell
docker compose ps
Invoke-WebRequest http://127.0.0.1:8080/healthz
docker compose logs --tail 200 api
docker compose logs --tail 200 db
docker compose logs --tail 200 web
```

`db`、`api`、`web` 均应显示 `healthy`。容器健康不替代业务验证；
升级后还应登录并验证值班表、任务查询和审计日志。

## 日常使用顺序

1. 组长登录并维护本地账号和角色。
2. 从“值班管理”下载固定模板，导入并确认至少今天与明天的二三线值班表。
3. 开发人员从“我要取号”提交版本发布或数据维护任务。
4. 当前负责人在任务详情中叫号、填写实际耗时并完成。
5. 人员不可参与时，由组长在“人员与可用性”中标记并预览重新分配。

## 数据备份

备份脚本只在数据库容器健康时执行，并生成无 BOM 的 UTF-8 SQL 文件：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/backup.ps1 `
  -OutputDirectory C:\opsqueue-backups `
  -EnvFile .env
```

脚本输出最终备份文件路径。应将备份复制到独立存储，并定期做恢复演练。

## 受保护的恢复

恢复会修改当前 `.env` 指向的数据库。必须同时提供备份文件和完全匹配的
`DB_NAME`，否则脚本拒绝执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/restore.ps1 `
  -InputFile C:\opsqueue-backups\ops-queue-20260726-120000.sql `
  -ConfirmDatabaseName ops_queue `
  -EnvFile .env
```

正式恢复前先停止业务写入并另做一次备份。优先在独立的 Compose 项目名和
新卷上验证备份，不要删除或复用生产卷。

## 停止与升级

保留数据卷停止：

```powershell
docker compose down
```

拉取代码或替换交付包后重新构建：

```powershell
docker compose up -d --build
docker compose ps
```

不要在未确认备份的情况下执行 `docker compose down -v`；`-v` 会删除
MySQL 命名卷。
