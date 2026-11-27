package com.proofflow.business.evidence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class PersistentEvidenceRepository implements EvidenceRepository {
    private final MySqlEvidenceMetadataRepository metadata;
    private final EvidenceObjectStore objects;

    PersistentEvidenceRepository(MySqlEvidenceMetadataRepository metadata, EvidenceObjectStore objects) {
        this.metadata = metadata;
        this.objects = objects;
    }

    @Override
    public boolean insert(String tenantId, Metadata attachment, byte[] content) {
        if (attachment.sizeBytes() != content.length) {
            throw new IllegalArgumentException("Evidence metadata size must match the content");
        }
        String key = "case-evidence/" + UUID.randomUUID();
        objects.put(key, content);
        // Keep the object if SQL fails. A connection error can hide a successful commit.
        metadata.insert(tenantId, new MySqlEvidenceMetadataRepository.Entry(
            attachment.id(), attachment.caseId(), attachment.filename(), attachment.contentType(),
            attachment.sizeBytes(), attachment.uploadedAt(), attachment.uploadedBy(), key));
        return true;
    }

    @Override
    public List<Metadata> list(String tenantId, UUID caseId) {
        return metadata.list(tenantId, caseId).stream()
            .map(PersistentEvidenceRepository::toMetadata)
            .toList();
    }

    @Override
    public Optional<Attachment> find(String tenantId, UUID caseId, UUID evidenceId) {
        return metadata.find(tenantId, caseId, evidenceId)
            .map(entry -> new Attachment(toMetadata(entry), objects.read(entry.objectKey(), entry.sizeBytes())));
    }

    private static Metadata toMetadata(MySqlEvidenceMetadataRepository.Entry entry) {
        return new Metadata(entry.id(), entry.caseId(), entry.filename(), entry.contentType(),
            entry.sizeBytes(), entry.uploadedAt(), entry.uploadedBy());
    }
}
