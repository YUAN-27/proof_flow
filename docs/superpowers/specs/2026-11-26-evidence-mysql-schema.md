# 工单附件 MySQL 表结构

在已执行 V001 的数据库上执行 [V002](../../../infra/mysql/migrations/V002__case_evidence.sql)。`case_evidence` 保存工单附件的显示名称、识别出的文件类型、字节数、上传人和上传时间，以及文件在对象存储中的键。文件内容保存在对象存储。

读取和写入元数据时使用服务端确定的 `tenant_id`，并带上 `case_id`。复合外键拒绝关联到其他租户的工单。列表按 `uploaded_at` 和 `evidence_id` 排序，可在上传时间相同时保持顺序稳定。上传时间使用 UTC 和微秒精度。

对象键由服务端生成，全局唯一。原始文件名只用于显示及下载，不参与拼接对象键。文件大小限制与附件 HTTP API 一致，为 1 字节至 10 MiB。文件类型仍由应用检查文件签名。

持久化适配器先成功保存对象，再提交 MySQL 元数据。查询仅返回已登记的附件。若元数据提交结果不确定，应先按对象键查询 MySQL 再决定是否清理对象，避免删掉已经发布的文件。

## 验收

在独立空库依次执行 V001、V002 和 [附件 SQL 验收](../../../tests/integration/mysql_case_evidence.sql)。验收覆盖同工单 ID 的租户隔离、列表顺序、跨租户外键拒绝、全局对象键唯一以及超限附件不入库。测试数据在验收结束时回滚。

`PersistentEvidenceRepository` 在 MinIO 确认写入后调用 `MySqlEvidenceMetadataRepository` 登记文件。下载先按租户、工单和附件 ID 查找元数据，再用保存的键读取文件。SQL 写入报错时保留对象，避免连接中断后误删已登记的文件。

## 使用 Compose 启用持久化

在仓库根目录准备 `.env`，按 README 配置工单联调凭据，再启动：

```powershell
docker compose -f compose.yaml -f compose.evidence.yaml up -d --build --wait
```

新 MySQL 数据卷会自动执行 V001 和 V002。已有数据卷需要先备份，再按顺序补跑尚未执行的迁移。以下命令以补跑 V002 为例，容器内使用 POSIX shell：

```powershell
Get-Content -Raw infra/mysql/migrations/V002__case_evidence.sql | docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"'
```

Java 启动时检查附件表和 MinIO bucket，默认 bucket 为 `proofflow-evidence`。Compose 使用现有 MinIO 本地凭据。单独运行 Java 时可配置 `PROOFFLOW_EVIDENCE_MINIO_ENDPOINT`、`PROOFFLOW_EVIDENCE_MINIO_ACCESS_KEY`、`PROOFFLOW_EVIDENCE_MINIO_SECRET_KEY` 和 `PROOFFLOW_EVIDENCE_MINIO_BUCKET`，并启用 MySQL 工单仓储及 `PROOFFLOW_EVIDENCE_PERSISTENCE=true`。

运行 `node tests/integration/evidence_persistence.mjs` 可在隔离 Compose 项目中验证上传、重启后的下载和租户隔离。脚本会移除该测试项目的数据卷。

运行 Java 验收时，先执行两份迁移，再按 [工单持久化验收指南](2026-11-25-mysql-case-persistence-design.md#验收) 配置 `PROOF_FLOW_TEST_MYSQL_*`。使用独立测试库，因为 Java 测试会保留合成工单和附件元数据。

```powershell
mvn -f apps/business-api/pom.xml "-Dtest=MySqlEvidenceMetadataRepositoryTest" test
```
