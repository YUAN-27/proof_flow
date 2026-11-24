# Java 工单 HTTP API

工单 API 复用内存工单服务。联调时只启用一个由服务器配置的租户与操作者，不包含登录功能。未配置 token 时，工单请求返回 `401`，公开的 `GET /api/v1/health` 仍可访问。

## Vite 热更新联调

在仓库根目录打开一个 PowerShell，用 JDK 21 和 Maven 启动新版 Java 服务。保持该终端运行：

```powershell
$env:PROOFFLOW_CASES_TOKEN = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:PROOFFLOW_CASES_TENANT_ID = 'demo-tenant'
$env:PROOFFLOW_CASES_ACTOR_ID = 'demo-operator'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8082'
mvn -f apps/business-api/pom.xml spring-boot:run
```

另开一个 PowerShell，从仓库根目录运行：

```powershell
$env:PROOFFLOW_API_TARGET = 'http://127.0.0.1:8082'
npm.cmd --prefix apps/frontend run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

首次运行前在前端安装项目依赖。打开 `http://127.0.0.1:5173/`，工作台使用 Java 进程配置的本地 token。在运行 Maven 前，可以用 `Set-Clipboard -Value $env:PROOFFLOW_CASES_TOKEN` 复制供页面填写。凭据不需要配置到 Vite。未设置代理目标时，Vite 仍使用原有的 `http://127.0.0.1:8080`。

## Docker Compose 联调

在工作树根目录准备 Compose 的 `.env`。在同一个 PowerShell 会话设置联调身份并启动服务：

```powershell
$env:PROOFFLOW_CASES_TOKEN = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:PROOFFLOW_CASES_TENANT_ID = 'demo-tenant'
$env:PROOFFLOW_CASES_ACTOR_ID = 'demo-operator'
$env:PROOFFLOW_WEB_PORT = '8081'
make up
```

token 由本地生成，保留在联调进程环境中。工单身份由服务端配置决定，请求中提供的租户或操作者字段不会改变身份。此凭据仅用于本地联调。

浏览器打开 `http://127.0.0.1:8081/`，在工单工作台输入同一个 token。页面支持创建工单、按 ID 查询和更新状态。遇到版本冲突时，先点击「刷新工单」。页面刷新后需要重新输入 token。

也可以用 PowerShell 直接调用 HTTP API：

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
