package com.proofflow.business.evidence;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stores metadata for objects that have already been written to object storage. */
final class MySqlEvidenceMetadataRepository {
    private static final String SELECT_FOR_CASE = """
        SELECT BIN_TO_UUID(evidence_id) AS evidence_uuid, filename, content_type, size_bytes,
               uploaded_at, uploaded_by, object_key
        FROM case_evidence WHERE tenant_id = ? AND case_id = UUID_TO_BIN(?)
        """;

    private final DataSource dataSource;

    MySqlEvidenceMetadataRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    void insert(String tenantId, Entry entry) {
        byte[] objectKey = entry.objectKey().getBytes(StandardCharsets.UTF_8);
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(entry.objectKey()) || objectKey.length > 255) {
            throw new IllegalArgumentException("Evidence object key must fit in 255 UTF-8 bytes");
        }
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
            INSERT INTO case_evidence
                (tenant_id, case_id, evidence_id, filename, content_type, size_bytes, uploaded_at, uploaded_by, object_key)
            VALUES (?, UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?)
            """)) {
            connection.setAutoCommit(true);
            bindCase(statement, tenantId, entry.caseId());
            statement.setString(3, entry.id().toString());
            statement.setString(4, entry.filename());
            statement.setString(5, entry.contentType());
            statement.setLong(6, entry.sizeBytes());
            var uploadedAt = entry.uploadedAt().truncatedTo(ChronoUnit.MICROS);
            statement.setObject(7, LocalDateTime.ofInstant(uploadedAt, ZoneOffset.UTC));
            statement.setString(8, entry.uploadedBy());
            statement.setBytes(9, objectKey);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Could not insert case evidence metadata", error);
        }
    }

    List<Entry> list(String tenantId, UUID caseId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SELECT_FOR_CASE + "ORDER BY uploaded_at, evidence_id")) {
            bindCase(statement, tenantId, caseId);
            try (var rows = statement.executeQuery()) {
                var entries = new ArrayList<Entry>();
                while (rows.next()) {
                    entries.add(readEntry(caseId, rows));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not list case evidence metadata", error);
        }
    }

    Optional<Entry> find(String tenantId, UUID caseId, UUID evidenceId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SELECT_FOR_CASE + "AND evidence_id = UUID_TO_BIN(?)")) {
            bindCase(statement, tenantId, caseId);
            statement.setString(3, evidenceId.toString());
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readEntry(caseId, rows)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not read case evidence metadata", error);
        }
    }

    private static void bindCase(PreparedStatement statement, String tenantId, UUID caseId) throws SQLException {
        statement.setString(1, tenantId);
        statement.setString(2, caseId.toString());
    }

    private static Entry readEntry(UUID caseId, ResultSet rows) throws SQLException {
        return new Entry(UUID.fromString(rows.getString("evidence_uuid")), caseId, rows.getString("filename"),
            rows.getString("content_type"), rows.getLong("size_bytes"),
            rows.getObject("uploaded_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rows.getString("uploaded_by"),
            new String(rows.getBytes("object_key"), StandardCharsets.UTF_8));
    }

    record Entry(UUID id, UUID caseId, String filename, String contentType, long sizeBytes, Instant uploadedAt,
                 String uploadedBy, String objectKey) {}
}
