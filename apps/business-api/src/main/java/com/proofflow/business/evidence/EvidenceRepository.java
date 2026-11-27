package com.proofflow.business.evidence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface EvidenceRepository {
    /** Publishes metadata and content together. Returns false if the repository has no capacity. */
    boolean insert(String tenantId, Metadata metadata, byte[] content);

    List<Metadata> list(String tenantId, UUID caseId);

    Optional<Attachment> find(String tenantId, UUID caseId, UUID evidenceId);

    record Metadata(UUID id, UUID caseId, String filename, String contentType, long sizeBytes,
                    Instant uploadedAt, String uploadedBy) {}

    record Attachment(Metadata metadata, byte[] content) {}
}
