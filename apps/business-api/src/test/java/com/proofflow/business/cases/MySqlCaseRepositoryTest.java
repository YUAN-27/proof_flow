package com.proofflow.business.cases;

import com.proofflow.business.cases.CaseService.AuditEntry;
import com.proofflow.business.cases.CaseService.CaseSnapshot;
import com.proofflow.business.cases.CaseService.Operation;
import com.proofflow.business.cases.CaseService.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "PROOF_FLOW_TEST_MYSQL_URL", matches = "jdbc:mysql:.+")
class MySqlCaseRepositoryTest extends CaseRepositoryContractTest {
    private static final Instant NOW = Instant.parse("2026-11-24T03:00:00.123456789Z");

    @Override
    CaseRepository newRepository() {
        try {
            Class<?> type = Class.forName("com.mysql.cj.jdbc.MysqlDataSource");
            var dataSource = (DataSource) type.getConstructor().newInstance();
            type.getMethod("setURL", String.class).invoke(dataSource, System.getenv("PROOF_FLOW_TEST_MYSQL_URL"));
            type.getMethod("setUser", String.class).invoke(dataSource, System.getenv("PROOF_FLOW_TEST_MYSQL_USER"));
            type.getMethod("setPassword", String.class).invoke(dataSource, System.getenv("PROOF_FLOW_TEST_MYSQL_PASSWORD"));
            return new MySqlCaseRepository(dataSource);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Add MySQL Connector/J to the test classpath", error);
        }
    }

    @Test
    void rollsBackBothCreationAndUpdateWhenMySqlRejectsTheAudit() {
        CaseRepository repository = newRepository();
        UUID id = UUID.randomUUID();
        var open = new CaseSnapshot(id, "tenant", "Title", Status.OPEN, 1);
        var updated = new CaseSnapshot(id, "tenant", "Title", Status.IN_REVIEW, 2);
        var invalidCreation = audit(open, Operation.STATUS_UPDATED, Status.OPEN);
        var creation = audit(open, Operation.CREATED, null);
        var invalidUpdate = audit(updated, Operation.STATUS_UPDATED, null);

        assertThatThrownBy(() -> repository.insert(open, invalidCreation)).hasCauseInstanceOf(SQLException.class);
        assertThat(repository.find("tenant", id)).isEmpty();
        assertThat(repository.auditTrail("tenant", id)).isEmpty();

        assertThat(repository.insert(open, creation)).isTrue();
        var originalHistory = repository.auditTrail("tenant", id);
        assertThatThrownBy(() -> repository.updateIfVersionMatches(1, updated, invalidUpdate))
            .hasCauseInstanceOf(SQLException.class);
        assertThat(repository.find("tenant", id)).contains(open);
        assertThat(repository.auditTrail("tenant", id)).isEqualTo(originalHistory);
        assertThat(repository.updateIfVersionMatches(1, updated, audit(updated, Operation.STATUS_UPDATED, Status.OPEN)))
            .isTrue();
    }

    @Test
    void roundTripsUnicodeAndTruncatesUtcAuditTimeToMicroseconds() {
        CaseRepository repository = newRepository();
        UUID id = UUID.randomUUID();
        var open = new CaseSnapshot(id, "租户🙂 ", "工单 🧪", Status.OPEN, 1);

        assertThat(repository.insert(open, audit(open, Operation.CREATED, null))).isTrue();
        assertThat(repository.find(open.tenantId(), id)).contains(open);
        assertThat(repository.find(open.tenantId().stripTrailing(), id)).isEmpty();
        assertThat(repository.auditTrail(open.tenantId(), id)).singleElement().satisfies(event -> {
            assertThat(event.actorId()).isEqualTo("操作者🙂");
            assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-24T03:00:00.123456Z"));
        });
    }

    private AuditEntry audit(CaseSnapshot snapshot, Operation operation, Status previousStatus) {
        return new AuditEntry(snapshot.tenantId(), "操作者🙂", snapshot.id(), operation, NOW,
            snapshot.version(), previousStatus, snapshot.status());
    }
}
