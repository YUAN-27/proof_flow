# ProofFlow 项目结构约定

- 修改前用 `git status --short --branch` 确认当前 checkout。涉及其他分支时，用 `git worktree list` 确认该分支的路径，并在对应 checkout 中工作。
- 可独立运行的服务放在 `apps/`。源码、服务依赖配置和单元测试与各自服务放在一起。
- 共享的 HTTP API 与事件契约放在 `contracts/`。基础设施定义放在 `infra/`，跨服务集成测试放在 `tests/integration/`，开发样例放在 `examples/`。
- 移动服务入口或目录后，核对 README、Makefile、Compose 和 CI 中的路径引用。

## Git Collaboration Rules

- GitHub `main` 分支是项目正式版本的唯一事实来源。
- 开始任务前确认当前分支、工作区状态及远程版本。
- 每个独立开发任务使用独立功能分支。
- 不擅自覆盖未提交的修改。
- 不直接向 `main` 推送代码。
- 创建 PR 前检查与最新 `main` 的差异。
- 遇到合并冲突时分析双方修改意图，不盲目覆盖。
- 合并前执行相关单元测试及集成测试。
- 通过 GitHub PR 和 CI 完成代码交付。
- 禁止未经授权的 `force push`、`reset --hard` 等破坏性操作。
