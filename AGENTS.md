# ProofFlow 项目结构约定

- 修改前用 `git status --short --branch` 确认当前 checkout。涉及其他分支时，用 `git worktree list` 确认该分支的路径，并在对应 checkout 中工作。
- 可独立运行的服务放在 `apps/`。源码、服务依赖配置和单元测试与各自服务放在一起。
- 共享的 HTTP API 与事件契约放在 `contracts/`。基础设施定义放在 `infra/`，跨服务集成测试放在 `tests/integration/`，开发样例放在 `examples/`。
- 移动服务入口或目录后，核对 README、Makefile、Compose 和 CI 中的路径引用。
