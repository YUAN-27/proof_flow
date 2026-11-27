# Java 工单 HTTP API

工单 API 支持内存模式和 MySQL 模式。联调时只启用一个由服务器配置的租户与操作者。未配置 token 时，工单请求返回 `401`，公开的 `GET /api/v1/health` 仍可访问。

## 客服会话 API

客服模式需要先应用 `V003__staff_accounts.sql`，由管理员为每位客服创建账号，并在 Java 环境中设置 `PROOFFLOW_CASES_REPOSITORY=mysql` 和 `PROOFFLOW_STAFF_ENABLED=true`。管理员只保存 BCrypt 密码哈希，为每位客服配置独立且稳定的 `actor_id`。账号禁用会阻止后续登录。已建立的会话会在退出、Java 进程重启或闲置 30 分钟时失效。

1. `GET /api/v1/staff/csrf` 返回 `headerName` 和 `token`。浏览器保存服务器的会话 cookie。
2. `POST /api/v1/staff/login` 以表单编码提交 `username` 和 `password`，并带上 CSRF header。成功返回 `204`，凭据无效返回 `401`。
3. 登录后重新获取 CSRF token。用 `GET /api/v1/staff/me` 获取租户和客服身份。带同一会话 cookie 请求工单与附件接口，写操作还需要 CSRF header。
4. `POST /api/v1/staff/logout` 带 CSRF header 退出，返回 `204`。

客服模式只接受会话身份，已有共享 Bearer token 在该模式下不可用。会话 cookie 仅经 HTTPS 传输。当前 Vue 工作台的登录页面将在下一批接入。

## Vite 热更新联调

准备 GNU Make、JDK 21、Maven 和 Node.js（22.12 或更高版本）。首次运行前在仓库根目录安装前端依赖，然后启动联调：

```powershell
npm --prefix apps/frontend ci
make dev
```

命令打包并启动 Java，随后启动 Vite 并配置代理。等待终端显示就绪，再打开 `http://127.0.0.1:5173/`。Vue 和 CSS 支持热更新。修改 Java 代码后重新运行 `make dev`。在当前终端按一次 `Ctrl+C` 关闭 Java 和 Vite。

首次启动会生成 Git 忽略的 `.env.dev`，工作台填写其中的 `PROOFFLOW_CASES_TOKEN`。脚本只把凭据传给 Java。可以在该文件里一次性设置 `JAVA_HOME`、`MVN` 的路径，以及 `PROOFFLOW_DEV_JAVA_PORT`、`PROOFFLOW_DEV_WEB_PORT`。默认使用 Java 的 `8082` 和 Vite 的 `5173`。端口被占用时请改这个文件，或者先停止占用端口的服务。直接使用 Vite 命令且不设置代理目标时，它仍代理到 `8080`。

通过 `make dev` 启动时，Maven 路径依次取 shell 环境中的 `MVN`、`.env.dev` 的 `MVN` 和 Make 的 `MVN`（例如在 `.env.make` 中配置）。

## Docker Compose 联调

首次运行前在工作树根目录复制 `.env.example` 为 `.env`，已有文件时保留它。在 `.env` 中一次性配置 `PROOFFLOW_WEB_PORT=8081`、`PROOFFLOW_CASES_TENANT_ID`、`PROOFFLOW_CASES_ACTOR_ID` 和 `PROOFFLOW_CASES_TOKEN`。联调身份和 token 可以沿用 `.env.dev` 中生成的值。之后 Compose 会自动读取 `.env`：

```powershell
make up
```

工单身份由服务端配置决定，请求中提供的租户或操作者字段不会改变身份。此凭据仅用于本地联调。

浏览器打开 `http://127.0.0.1:8081/`，在工单工作台输入同一个 token。页面支持创建工单、按 ID 查询和更新状态。遇到版本冲突时，先点击「刷新工单」。页面刷新后需要重新输入 token。

在同一目录运行 `make stop` 可停止 Compose 服务，保留容器和数据卷。

也可以用 PowerShell 直接调用 HTTP API。以下示例假设当前会话中的 `PROOFFLOW_CASES_TOKEN` 已设为与服务一致的值：

```powershell
$caseBase = 'http://127.0.0.1:8081/api/v1/cases'
$caseHeaders = @{ Authorization = "Bearer $env:PROOFFLOW_CASES_TOKEN" }
$createdCase = Invoke-RestMethod -Uri $caseBase -Method Post -Headers $caseHeaders -ContentType 'application/json' -Body '{"title":"Serial number missing"}'
Invoke-RestMethod -Uri "$caseBase/$($createdCase.id)" -Headers $caseHeaders
$caseUpdate = @{ expectedVersion = $createdCase.version; status = 'IN_REVIEW' } | ConvertTo-Json -Compress
Invoke-RestMethod -Uri "$caseBase/$($createdCase.id)/status" -Method Patch -Headers $caseHeaders -ContentType 'application/json' -Body $caseUpdate
```

创建成功返回 `201` 和 `Location`，查询与更新成功返回 `200`。无效输入返回 `400`，跨租户和不存在的工单都返回 `404`。版本冲突或非法状态流转返回 `409`，响应的 `code` 分别为 `VERSION_CONFLICT` 或 `INVALID_STATUS_TRANSITION`。每次成功更新后使用响应中的新版本继续操作。

工单和审计记录在进程重启后会清空。[OpenAPI 契约](../contracts/openapi/public.yaml)

## 工单证据附件

开发环境可以通过工单 API 上传、列出和下载附件，沿用工单的 bearer 凭据。租户由服务端确定，跨租户访问返回 `404`。

- `POST /api/v1/cases/{caseId}/evidence` 接收 `multipart/form-data` 的 `file` 字段。支持 PDF、PNG、JPEG 的文件签名，单文件限 10 MiB。成功返回 `201` 和下载地址。
- `GET /api/v1/cases/{caseId}/evidence` 返回附件元数据。
- `GET /api/v1/cases/{caseId}/evidence/{evidenceId}` 以附件形式下载原始文件。

内存模式最多保存 256 个附件及 64 MiB 文件内容，重启后会清空。持久化模式把附件元数据写入 MySQL，把文件写入 MinIO。用[持久化指南](superpowers/specs/2026-11-26-evidence-mysql-schema.md#使用-compose-启用持久化)启用该模式。
