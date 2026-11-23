# ProofFlow Investigator

ProofFlow 帮助客服整理售后争议的证据和缺口，生成带版本引用的调查报告，供人工审核。

| 目录 | 当前入口 |
| --- | --- |
| `frontend/` | Vue 3 + Vite 页面，通过同源代理检查 Java API |
| `business-api/` | Java 21 + Spring Boot，提供 `GET /api/v1/health` |
| `agent-service/` | Python + FastAPI，提供容器网络内的 `GET /health` |
| `contracts/` | 公开健康检查的 OpenAPI、事件信封 Schema 与示例 |
| `infra/minio/` | 从固定版本的社区版源码构建 MinIO |
| `casegym/` | 序列号争议的合成开发样例 |

## 本地启动

需要 Docker 与 Compose v2。在仓库根目录运行：

```sh
cp .env.example .env
docker compose config --quiet
docker compose up --build --wait
curl --fail http://127.0.0.1:8080/api/v1/health
docker compose ps
```

在浏览器打开 <http://127.0.0.1:8080/>。修改 `PROOFFLOW_WEB_PORT` 时同步调整访问地址。公开健康检查仅表示 Java 进程可以处理请求。MySQL 和 MinIO 使用各自的容器健康检查及持久卷，骨架阶段尚未接入业务存储。`.env.example` 中的密码用于本地演示。

## 单模块检查

```sh
(cd business-api && mvn test)
(cd frontend && npm install && npm run lint && npm run build)
(cd agent-service && python3 -m venv .venv && .venv/bin/python -m pip install -r requirements-dev.txt && .venv/bin/python -m pytest)
```

Java 需要 JDK 21 和 Maven，前端需要 Node.js 22.12 或更新版本，Agent 容器使用 Python 3.12。单独开发前端时，用 `mvn spring-boot:run` 启动 Java，再用 `npm run dev` 启动 Vite。

## 实现顺序

第一阶段从 Java 工单模块开始，建立租户授权、版本更新和审计，再接入证据、对象存储与 OCR。随后用 Python 自研 Runtime、Planner、Tool Registry、Context Builder、Skill Loader、Evidence Verifier 和 Checkpoint 适配器。Java 统一写入 MySQL，Python 通过 Java 内部 API 查询业务数据和提交命令。独立 Agent 和 OCR worker 随持久任务队列接入，CaseGym 随调查流程加入回放、故障注入和评分。
