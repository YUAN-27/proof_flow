# MySQL 工单存储设计

Spring Boot 默认使用内存 `CaseRepository`。启用 MySQL 模式后，工单快照和审计写入 MySQL 8.4。V001 由部署方在空库执行一次。

## 表结构

- `case_records` 保存当前快照。复合主键 `(tenant_id, case_id)` 支持租户内按工单 ID 查询和条件更新。
- `case_audit_entries` 按 `(tenant_id, case_id, version)` 记录审计。同一工单每个版本只有一条记录，复合外键也要求租户一致。历史可按版本排序。
- UUID 存为 16 字节，适配器负责在 Java UUID 和二进制之间转换。租户与操作者 ID 最长 191 个字符，二进制排序规则保留大小写和尾部空格的差异。标题使用 TEXT。适配器需要显式处理长度上限。
- 审计时间使用 UTC、精确到微秒。适配器写入前将 Java Instant 截断到微秒，读取时按 UTC 转回 Instant。审计顺序由版本决定。

## 适配器写入约定

创建时在一个事务内插入快照和创建审计。只有同租户同 ID 的快照主键冲突才应映射为仓储的 `false`，其它写入错误应回滚并抛出。更新时按租户、工单 ID 和期望版本执行条件 UPDATE。影响行数为零时返回 `false`，不要插入审计。有一行更新时在同一事务插入审计。审计写入失败时回滚快照更新。

## 启动 MySQL 模式

在目标数据库执行 [V001 迁移](../../../infra/mysql/migrations/V001__case_repository.sql)，为应用账号授予工单表的读取和写入权限。设置 `PROOFFLOW_CASES_REPOSITORY=mysql`，并从环境传入 `PROOFFLOW_CASES_MYSQL_URL`、`PROOFFLOW_CASES_MYSQL_USER` 和 `PROOFFLOW_CASES_MYSQL_PASSWORD`。JDBC URL 以 `jdbc:mysql://` 开头。工单 HTTP API 沿用 `PROOFFLOW_CASES_TOKEN`、`PROOFFLOW_CASES_TENANT_ID` 和 `PROOFFLOW_CASES_ACTOR_ID`。

从仓库根目录构建 Java 包，将外部 MySQL Connector/J JAR 的绝对路径赋给 PowerShell 变量 `$mysqlConnectorJar`，再启动：

```powershell
mvn -f apps/business-api/pom.xml package
java "-Dloader.path=$mysqlConnectorJar" -cp apps/business-api/target/business-api-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher --server.address=127.0.0.1 --server.port=8082
```

应用启动时检查连接和两张表。连接失败或缺少表会终止启动。MySQL 模式使用 Connector/J 的 `DataSource`，每次仓储操作获取并关闭连接。

## 验收

- 用一次性空库执行 [V001 迁移](../../../infra/mysql/migrations/V001__case_repository.sql) 和 [SQL 验收](../../../tests/integration/mysql_case_repository.sql)。检查复合键、重复创建、跨租户审计拒绝、版本冲突和事务回滚。
- 用两个连接同时更新同一个版本，确认只提交一个新快照和一条新审计。
- 重启使用同一数据卷的 MySQL，确认快照和审计仍可查询。
- `MySqlCaseRepositoryTest` 复用 `CaseRepositoryContractTest`，另验证 MySQL 拒绝审计后回滚创建和更新，以及 Unicode 和微秒时间往返。
- `MySqlCaseHttpIntegrationTest` 通过 HTTP 创建工单并更新状态，关闭并重新启动 Spring Boot 后读取同一工单及两条按版本排序的审计记录。

在一次性测试数据库执行 V001 后，设置 `PROOF_FLOW_TEST_MYSQL_URL`、`PROOF_FLOW_TEST_MYSQL_USER` 和 `PROOF_FLOW_TEST_MYSQL_PASSWORD`，并把 MySQL Connector/J JAR 的绝对路径赋给 PowerShell 变量 `$mysqlConnectorJar`。从仓库根目录运行：

```powershell
mvn -f apps/business-api/pom.xml "-Dmaven.test.additionalClasspath=$mysqlConnectorJar" "-Dtest=MySqlCaseRepositoryTest,MySqlCaseHttpIntegrationTest" test
```

测试会写入工单和审计数据，并在测试库中保留这些数据。未设置 JDBC URL 时，常规 Maven 测试跳过这两组 MySQL 测试。
