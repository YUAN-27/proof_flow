# MySQL 工单存储设计

本轮准备 MySQL 8.4 表结构和 SQL 验收用例。Spring 仍使用内存 `CaseRepository`。SQL 文件仅供隔离验证，尚未挂到 Compose 或应用启动过程。

## 表结构

- `case_records` 保存当前快照。复合主键 `(tenant_id, case_id)` 支持租户内按工单 ID 查询和条件更新。
- `case_audit_entries` 按 `(tenant_id, case_id, version)` 记录审计。同一工单每个版本只有一条记录，复合外键也要求租户一致。历史可按版本排序。
- UUID 存为 16 字节，适配器负责在 Java UUID 和二进制之间转换。租户与操作者 ID 最长 191 个字符，二进制排序规则保留大小写和尾部空格的差异。标题使用 TEXT。适配器需要显式处理长度上限。
- 审计时间使用 UTC、精确到微秒。适配器写入前将 Java Instant 截断到微秒，读取时按 UTC 转回 Instant。审计顺序由版本决定。

## 适配器写入约定

创建时在一个事务内插入快照和创建审计。只有同租户同 ID 的快照主键冲突才应映射为仓储的 `false`，其它写入错误应回滚并抛出。更新时按租户、工单 ID 和期望版本执行条件 UPDATE。影响行数为零时返回 `false`，不要插入审计。有一行更新时在同一事务插入审计。审计写入失败时回滚快照更新。

## 验收

- 用一次性空库执行 [V001 迁移](../../../infra/mysql/migrations/V001__case_repository.sql) 和 [SQL 验收](../../../tests/integration/mysql_case_repository.sql)。检查复合键、重复创建、跨租户审计拒绝、版本冲突和事务回滚。
- 用两个连接同时更新同一个版本，确认只提交一个新快照和一条新审计。
- 重启使用同一数据卷的 MySQL，确认快照和审计仍可查询。
- 实现 Java 适配器后，复用 `CaseRepositoryContractTest`，并补一个由实际适配器触发审计错误的事务回滚用例。
