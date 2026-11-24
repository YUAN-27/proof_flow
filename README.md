# ProofFlow Investigator

ProofFlow 帮助客服整理售后争议的证据和缺口，生成带版本引用的调查报告，供人工审核。

## 项目结构

根目录的 `Makefile`、`compose.yaml` 和 `.github/workflows/` 分别提供任务命令、容器编排和 CI 配置。

| 目录 | 用途 |
| --- | --- |
| `apps/frontend/` | Vue 3 + Vite 页面，通过同源代理检查 Java API |
| `apps/business-api/` | Java 21 + Spring Boot，提供 `GET /api/v1/health` |
| `apps/agent-service/` | Python + FastAPI，提供容器网络内的 `GET /health`；依赖由 uv 管理 |
| `contracts/` | 公开健康检查的 OpenAPI、事件信封 Schema 与示例 |
| `infra/minio/` | 从固定版本的社区版源码构建 MinIO |
| `examples/casegym/` | 序列号争议的合成开发样例 |
| `docs/` | 项目设计与技术文档 |

源码、依赖配置和单元测试与各自的服务放在一起。跨服务集成测试可以在需要时放入根目录的 `tests/`。

## 本地启动

Windows、Linux 和 macOS 共用仓库根目录的 `Makefile`。安装 GNU Make 与 Docker Compose v2 后，运行 `make help` 查看命令。

首次启动前，在仓库根目录复制环境文件。Windows PowerShell 执行 `Copy-Item .env.example .env`，Linux 和 macOS 的 shell 执行 `cp .env.example .env`。已有 `.env` 时保留现有文件。然后运行：

```powershell
make config
make up
docker compose ps
```

运行 `make` 或 `make build` 只构建镜像。

需要 Docker 与 Compose v2。也可以在仓库根目录直接运行下列命令（POSIX shell）：

```sh
cp .env.example .env
docker compose config --quiet
docker compose up --build --wait
curl --fail http://127.0.0.1:8080/api/v1/health
docker compose ps
```

在浏览器打开 <http://127.0.0.1:8080/>。修改 `PROOFFLOW_WEB_PORT` 时同步调整访问地址。公开健康检查仅表示 Java 进程可以处理请求。MySQL 和 MinIO 使用各自的容器健康检查及持久卷，骨架阶段尚未接入业务存储。`.env.example` 中的密码用于本地演示。

## 单模块检查

准备各模块的工具和依赖后，运行 `make test` 完成全部检查。也可以单独运行 `make test-backend`、`make test-frontend` 或 `make test-agent`。前端目标执行 lint 和构建。Agent 测试需要 Python 3.12 和 uv，uv 会创建虚拟环境并按 `uv.lock` 同步测试依赖。

```sh
(cd apps/business-api && mvn test)
(cd apps/frontend && npm install && npm run lint && npm run build)
(cd apps/agent-service && uv run --locked pytest)
```

Java 需要 JDK 21 和 Maven，前端需要 Node.js 22.12 或更新版本，Agent 容器使用 Python 3.12。单独开发前端时，在 `apps/business-api/` 运行 `mvn spring-boot:run`，再在 `apps/frontend/` 运行 `npm run dev`。

修改 Agent 的 `pyproject.toml` 后，在 `apps/agent-service/` 运行 `uv lock`，将更新后的 `uv.lock` 一并提交。Docker 构建从同一份锁文件安装运行依赖。

## GitHub Actions

向 `main` 提交 PR 或推送时，CI 在 Linux 和 macOS 上运行 Java 和 Agent 单元测试、前端 lint 与构建。Linux job 还校验 Compose 配置。

## 实现顺序

第一阶段从 Java 工单模块开始，建立租户授权、版本更新和审计，再接入证据、对象存储与 OCR。随后用 Python 自研 Runtime、Planner、Tool Registry、Context Builder、Skill Loader、Evidence Verifier 和 Checkpoint 适配器。Java 统一写入 MySQL，Python 通过 Java 内部 API 查询业务数据和提交命令。独立 Agent 和 OCR worker 随持久任务队列接入，CaseGym 随调查流程加入回放、故障注入和评分。
