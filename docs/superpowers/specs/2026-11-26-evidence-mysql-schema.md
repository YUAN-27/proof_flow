# 工单附件 MySQL 表结构

在已执行 V001 的数据库上执行 [V002](../../../infra/mysql/migrations/V002__case_evidence.sql)。`case_evidence` 保存工单附件的显示名称、识别出的文件类型、字节数、上传人和上传时间，以及文件在对象存储中的键。文件内容保存在对象存储。

读取和写入元数据时使用服务端确定的 `tenant_id`，并带上 `case_id`。复合外键拒绝关联到其他租户的工单。列表按 `uploaded_at` 和 `evidence_id` 排序，可在上传时间相同时保持顺序稳定。上传时间使用 UTC 和微秒精度。

对象键由服务端生成，全局唯一。原始文件名只用于显示及下载，不参与拼接对象键。文件大小限制与附件 HTTP API 一致，为 1 字节至 10 MiB。文件类型仍由应用检查文件签名。

持久化适配器先成功保存对象，再提交 MySQL 元数据。查询仅返回已登记的附件。若元数据提交结果不确定，应先按对象键查询 MySQL 再决定是否清理对象，避免删掉已经发布的文件。

## 验收

在独立空库依次执行 V001、V002 和 [附件 SQL 验收](../../../tests/integration/mysql_case_evidence.sql)。验收覆盖同工单 ID 的租户隔离、列表顺序、跨租户外键拒绝、全局对象键唯一以及超限附件不入库。测试数据在验收结束时回滚。

`MySqlEvidenceMetadataRepository` 通过 JDBC 保存元数据并按租户和工单读取列表或单条记录。MinIO 适配器接入后，由它在对象保存成功时调用此仓储登记。附件 HTTP API 当前仍使用内存实现。

运行 Java 验收时，先执行两份迁移，再按 [工单持久化验收指南](2026-11-25-mysql-case-persistence-design.md#验收) 配置 `PROOF_FLOW_TEST_MYSQL_*` 和 Connector/J。使用独立测试库，因为 Java 测试会保留合成工单和附件元数据。

```powershell
mvn -f apps/business-api/pom.xml "-Dmaven.test.additionalClasspath=$mysqlConnectorJar" "-Dtest=MySqlEvidenceMetadataRepositoryTest" test
```
