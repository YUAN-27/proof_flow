# Java 工单 HTTP API

工单 API 复用内存工单服务。联调时只启用一个由服务器配置的租户与操作者，不包含登录功能。未配置 token 时，工单请求返回 `401`，公开的 `GET /api/v1/health` 仍可访问。

## 本地联调

在工作树根目录准备 Compose 的 `.env`。在同一个 PowerShell 会话设置联调身份并启动服务：

```powershell
$env:PROOFFLOW_CASES_TOKEN = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:PROOFFLOW_CASES_TENANT_ID = 'demo-tenant'
$env:PROOFFLOW_CASES_ACTOR_ID = 'demo-operator'
$env:PROOFFLOW_WEB_PORT = '8081'
make up
```

token 由本地生成，保留在联调进程环境中。工单身份由服务端配置决定，请求中提供的租户或操作者字段不会改变身份。此凭据仅用于本地联调。

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
