package com.proofflow.business.cases;

import com.proofflow.business.cases.CaseService.AuditEntry;
import com.proofflow.business.cases.CaseService.CaseSnapshot;
import com.proofflow.business.cases.CaseService.Operation;
import com.proofflow.business.cases.CaseService.Status;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** JDBC adapter for the MySQL case schema. Each write owns its connection and transaction. */
public final class MySqlCaseRepository implements CaseRepository {
    private static final int DUPLICATE_KEY = 1062;
    private static final int MAX_ID_CHARACTERS = 191;
    private static final int MAX_TITLE_BYTES = 65_535;

    private final DataSource dataSource;

    public MySqlCaseRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean insert(CaseSnapshot snapshot, AuditEntry audit) {
        validateWrite(snapshot, audit);
        return writeWithAudit(audit, connection -> {
            try (var statement = connection.prepareStatement("""
                INSERT INTO case_records (tenant_id, case_id, title, status, version) VALUES (?, ?, ?, ?, ?)
                """)) {
                bindKey(statement, 1, snapshot.tenantId(), snapshot.id());
                statement.setString(3, snapshot.title());
                statement.setString(4, snapshot.status().name());
                statement.setLong(5, snapshot.version());
                try {
                    statement.executeUpdate();
                    return true;
                } catch (SQLException error) {
                    if (error.getErrorCode() == DUPLICATE_KEY && "23000".equals(error.getSQLState())) {
                        return false;
                    }
                    throw error;
                }
            }
        });
    }

    @Override
    public Optional<CaseSnapshot> find(String tenantId, UUID caseId) {
        validateId(tenantId, "tenantId");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
            SELECT title, status, version FROM case_records WHERE tenant_id = ? AND case_id = ?
            """)) {
            bindKey(statement, 1, tenantId, caseId);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    return Optional.of(new CaseSnapshot(caseId, tenantId, rows.getString("title"),
                        Status.valueOf(rows.getString("status")), rows.getLong("version")));
                }
                return Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not read the case snapshot", error);
        }
    }

    @Override
    public boolean updateIfVersionMatches(long expectedVersion, CaseSnapshot snapshot, AuditEntry audit) {
        validateWrite(snapshot, audit);
        return writeWithAudit(audit, connection -> {
            try (var statement = connection.prepareStatement("""
                UPDATE case_records SET title = ?, status = ?, version = ?
                WHERE tenant_id = ? AND case_id = ? AND version = ?
                """)) {
                statement.setString(1, snapshot.title());
                statement.setString(2, snapshot.status().name());
                statement.setLong(3, snapshot.version());
                bindKey(statement, 4, snapshot.tenantId(), snapshot.id());
                statement.setLong(6, expectedVersion);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public List<AuditEntry> auditTrail(String tenantId, UUID caseId) {
        validateId(tenantId, "tenantId");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
            SELECT actor_id, operation, occurred_at, version, previous_status, status
            FROM case_audit_entries WHERE tenant_id = ? AND case_id = ? ORDER BY version
            """)) {
            bindKey(statement, 1, tenantId, caseId);
            try (var rows = statement.executeQuery()) {
                var history = new ArrayList<AuditEntry>();
                while (rows.next()) {
                    String previous = rows.getString("previous_status");
                    history.add(new AuditEntry(tenantId, rows.getString("actor_id"), caseId,
                        Operation.valueOf(rows.getString("operation")),
                        rows.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rows.getLong("version"), previous == null ? null : Status.valueOf(previous),
                        Status.valueOf(rows.getString("status"))));
                }
                return List.copyOf(history);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not read the case audit trail", error);
        }
    }

    private boolean writeWithAudit(AuditEntry audit, SnapshotWrite write) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!write.execute(connection)) {
                    connection.rollback();
                    return false;
                }
                insertAudit(connection, audit);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not write the case snapshot and audit", error);
        }
    }

    private static void insertAudit(Connection connection, AuditEntry audit) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO case_audit_entries
                (tenant_id, case_id, version, actor_id, operation, occurred_at, previous_status, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            bindKey(statement, 1, audit.tenantId(), audit.caseId());
            statement.setLong(3, audit.version());
            statement.setString(4, audit.actorId());
            statement.setString(5, audit.operation().name());
            statement.setObject(6, LocalDateTime.ofInstant(audit.occurredAt().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
            statement.setString(7, audit.previousStatus() == null ? null : audit.previousStatus().name());
            statement.setString(8, audit.status().name());
            statement.executeUpdate();
        }
    }

    private static void bindKey(PreparedStatement statement, int index, String tenantId, UUID caseId)
        throws SQLException {
        statement.setString(index, tenantId);
        statement.setBytes(index + 1, ByteBuffer.allocate(16)
            .putLong(caseId.getMostSignificantBits()).putLong(caseId.getLeastSignificantBits()).array());
    }

    private static void validateWrite(CaseSnapshot snapshot, AuditEntry audit) {
        Objects.requireNonNull(audit, "audit");
        validateId(snapshot.tenantId(), "tenantId");
        validateId(audit.actorId(), "actorId");
        if (!snapshot.tenantId().equals(audit.tenantId()) || !snapshot.id().equals(audit.caseId())
            || snapshot.version() != audit.version() || snapshot.status() != audit.status()) {
            throw new IllegalArgumentException("Audit must match the case tenant, ID, version, and status");
        }
        String title = snapshot.title();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(title)
            || title.getBytes(StandardCharsets.UTF_8).length > MAX_TITLE_BYTES) {
            throw new IllegalArgumentException("title must fit in a MySQL UTF-8 TEXT column");
        }
    }

    private static void validateId(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.codePointCount(0, value.length()) > MAX_ID_CHARACTERS
            || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException(field + " must fit in a MySQL UTF-8 VARCHAR(191) column");
        }
    }

    @FunctionalInterface
    private interface SnapshotWrite {
        boolean execute(Connection connection) throws SQLException;
    }
}
