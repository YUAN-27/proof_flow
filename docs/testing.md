# ProofFlow 测试指南

以下命令都从仓库根目录运行。

## 单元测试和模块检查

```text
make test
```

运行前需准备 GNU Make、JDK 21 和 Maven、Python 3.12 和 uv，以及 Node.js 22.12 或更高版本。首次运行前端检查时，先执行 `npm --prefix apps/frontend install`。Agent 的测试依赖由 uv 从 `uv.lock` 同步。

- `make test-backend` 使用 Maven 运行 Java 测试。现有健康接口测试加载 Spring 应用上下文并使用 MockMvc，属于模块内 HTTP 测试。
- `make test-agent` 使用 pytest 和 FastAPI TestClient 验证 Agent 的 `/health`。请求在 Python 进程内完成。
- `make test-frontend` 运行 ESLint 和 Vite 构建。目前没有前端单元测试。

这些检查不需要启动 Compose。Java 和 Agent 的健康接口测试也不访问其他服务。

## 跨服务集成测试

需准备 Docker、Compose v2 和 Python 3。首次启动前复制根目录的 `.env.example` 为 `.env`。已有 `.env` 时保留它。

```text
make up
make test-integration
```

`make up` 构建镜像、启动容器并等待健康检查。`make test-integration` 对正在运行的 Compose 实例发送真实 HTTP 请求，检查首页和通过前端代理访问的 Java 健康接口。测试不创建或修改业务数据。

默认地址为 `http://127.0.0.1:8080`。如果在 Compose 中设置了其他端口，可在运行测试前指定地址。例如在 PowerShell 中：

```powershell
$env:PROOFFLOW_SMOKE_URL = 'http://127.0.0.1:8081'
make test-integration
```

这组只读 smoke test 覆盖前端到 Java 的请求链路。Agent、MySQL 和 MinIO 的就绪状态由 Compose 健康检查确认。目前没有 Agent 业务调用、数据库写入或对象存储读写测试。

工单 API 的写入回归单独使用 `make test-case-integration`，只在可丢弃的联调环境运行。它使用该环境的 `PROOFFLOW_CASES_TOKEN`，经前端代理验证无凭据拒绝、创建、读取、状态流转和版本冲突，并会创建一张合成工单。

## GitHub Actions

面向 `main` 的 PR 和推送到 `main` 都会触发 CI。Linux 与 macOS 都执行 `make test`。Linux 生成临时联调凭据，再执行 `make config`、`make up`、`make test-integration` 和 `make test-case-integration`。启动或测试失败时，在 Actions job 中查看 Compose 日志。job 结束时清理 runner 上的临时容器和数据卷。
