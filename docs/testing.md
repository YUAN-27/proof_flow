# ProofFlow 测试指南

以下命令都从仓库根目录运行。

## 单元测试和模块检查

```text
npm --prefix apps/frontend ci
make test
```

本地 `make test` 运行 Java 和 Agent 测试，以及前端 lint 和构建。需要 GNU Make、JDK 21、Maven、Python 3.12、uv、Node.js 22.12 或更高版本，以及 npm。Agent 的测试依赖由 uv 从 `uv.lock` 同步。

如果电脑上装了多个 JDK，可以在本地 `.env.make` 里用 `JAVA_HOME :=` 指定 JDK 21 的安装路径。路径有空格时也直接填写，文件由 Git 忽略。

- `make test-backend` 使用 Maven 运行 Java 测试。现有健康接口测试加载 Spring 应用上下文并使用 MockMvc，属于模块内 HTTP 测试。
- `make test-agent` 使用 pytest 和 FastAPI TestClient 验证 Agent 的 `/health`。请求在 Python 进程内完成。
- `make test-frontend` 运行 ESLint 和 Vite 构建。目前没有前端单元测试。运行前需准备 Node.js 22.12 或更高版本，并执行 `npm --prefix apps/frontend ci` 安装锁定的依赖。

这些检查不需要启动 Compose。Java 和 Agent 的健康接口测试也不访问其他服务。

## 推送前检查

新 clone 可执行 `git config --local core.hooksPath .githooks` 启用仓库提供的 Git hooks。推送前，hook 将每个待推送 ref 指向的 commit 解包到临时目录。包含前端 lockfile 的快照会先执行 `npm ci` 安装依赖，再运行该快照的 `make test`。多个 ref 指向同一 commit 时只测试一次，删除 ref 时跳过测试。依赖安装或测试失败会终止推送。请在运行 Git 的终端中配置上述本地测试工具。hook 也会读取当前 checkout 的 `.env.make` 作为本机工具配置。

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

这组只读 smoke test 覆盖前端到 Java 的请求链路。Agent、MySQL 和 MinIO 的就绪状态由 Compose 健康检查确认。

工单 API 的写入回归单独使用 `make test-case-integration`，只在可丢弃的联调环境运行。它使用该环境的 `PROOFFLOW_CASES_TOKEN`，经前端代理验证无凭据拒绝、创建、读取、状态流转和版本冲突，并会创建一张合成工单。

`make test-evidence-integration` 会启动独立的 Compose 项目，验证上传、Java 重启后的列表及逐字节下载，以及跨租户访问返回 `404`。需要 Docker、Compose v2 和 Node.js。脚本使用随机项目名、端口和凭据，并在测试后清理该项目的容器和数据卷。

## GitHub Actions

面向 `main` 的 PR 和推送到 `main` 都会触发 CI。Linux 与 macOS 使用 `npm ci` 安装前端依赖，然后执行 `make test`。Linux 先运行 `make test-integration` 和 `make test-case-integration`，清理联调环境后再运行 `make test-evidence-integration`。启动或测试失败时，在 Actions job 中查看 Compose 日志。
