package com.proofflow.business.evidence;

import com.proofflow.business.cases.CaseService.AuditEntry;
import com.proofflow.business.cases.CaseService.CaseSnapshot;
import com.proofflow.business.cases.CaseService.Operation;
import com.proofflow.business.cases.CaseService.Status;
import com.proofflow.business.cases.MySqlCaseRepository;
import com.proofflow.business.evidence.MySqlEvidenceMetadataRepository.Entry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.jdbc.DataSourceBuilder;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "PROOF_FLOW_TEST_MYSQL_URL", matches = "jdbc:mysql:.+")
class MySqlEvidenceMetadataRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-11-26T00:00:00.123456789Z");
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);

    private final UUID caseId = UUID.randomUUID();
    private MySqlCaseRepository cases;
    private MySqlEvidenceMetadataRepository evidence;

    @BeforeEach
    void connect() throws ClassNotFoundException {
        DataSource dataSource = DataSourceBuilder.create()
            .type(Class.forName("com.mysql.cj.jdbc.MysqlDataSource").asSubclass(DataSource.class))
            .url(System.getenv("PROOF_FLOW_TEST_MYSQL_URL"))
            .username(System.getenv("PROOF_FLOW_TEST_MYSQL_USER"))
            .password(System.getenv("PROOF_FLOW_TEST_MYSQL_PASSWORD"))
            .build();
        cases = new MySqlCaseRepository(dataSource);
        evidence = new MySqlEvidenceMetadataRepository(dataSource);
    }

    @Test
    void readsMetadataForTheMatchingTenantAndCaseInUploadOrder() {
        createCase("tenant-a", caseId);
        createCase("tenant-b", caseId);
        UUID otherCase = UUID.randomUUID();
        createCase("tenant-a", otherCase);
        var first = attachment(FIRST, caseId, newObjectKey());
        var second = attachment(SECOND, caseId, newObjectKey());
        evidence.insert("tenant-a", second);
        evidence.insert("tenant-a", first);
        evidence.insert("tenant-b", attachment(SECOND, caseId, newObjectKey()));
        evidence.insert("tenant-a", attachment(SECOND, otherCase, newObjectKey()));

        assertThat(evidence.list("tenant-a", caseId)).extracting(Entry::id).containsExactly(FIRST, SECOND);
        assertThat(evidence.list("tenant-b", caseId)).extracting(Entry::id).containsExactly(SECOND);
        assertThat(evidence.find("tenant-b", caseId, FIRST)).isEmpty();
        assertThat(evidence.find("tenant-a", otherCase, FIRST)).isEmpty();
        assertThat(evidence.find("tenant-a", caseId, FIRST)).contains(new Entry(first.id(), first.caseId(),
            first.filename(), first.contentType(), first.sizeBytes(), NOW.truncatedTo(ChronoUnit.MICROS),
            first.uploadedBy(), first.objectKey()));
    }

    @Test
    void leavesNoMetadataWhenTheCaseOrObjectKeyFailsAConstraint() {
        createCase("tenant-a", caseId);
        String key = newObjectKey();
        var metadata = attachment(FIRST, caseId, key);
        assertThatThrownBy(() -> evidence.insert("tenant-b", metadata)).hasCauseInstanceOf(SQLException.class);
        assertThat(evidence.list("tenant-b", caseId)).isEmpty();

        evidence.insert("tenant-a", metadata);
        assertThatThrownBy(() -> evidence.insert("tenant-a", attachment(SECOND, caseId, key)))
            .hasCauseInstanceOf(SQLException.class);
        assertThat(evidence.find("tenant-a", caseId, SECOND)).isEmpty();
        assertThat(evidence.list("tenant-a", caseId)).extracting(Entry::id).containsExactly(FIRST);
    }

    private void createCase(String tenant, UUID id) {
        var snapshot = new CaseSnapshot(id, tenant, "Evidence fixture", Status.OPEN, 1);
        var audit = new AuditEntry(tenant, "creator", id, Operation.CREATED, NOW, 1, null, Status.OPEN);
        assertThat(cases.insert(snapshot, audit)).isTrue();
    }

    private static Entry attachment(UUID id, UUID caseId, String objectKey) {
        return new Entry(id, caseId, "凭证.pdf", "application/pdf", 12, NOW, "操作者", objectKey);
    }

    private static String newObjectKey() {
        return "evidence/" + UUID.randomUUID();
    }
}
