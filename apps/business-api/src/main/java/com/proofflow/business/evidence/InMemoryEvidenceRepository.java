package com.proofflow.business.evidence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "proofflow.cases.repository", havingValue = "memory", matchIfMissing = true)
class InMemoryEvidenceRepository implements EvidenceRepository {
    private static final int MAX_STORED_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ATTACHMENTS = 256;
    private final Map<CaseKey, Map<UUID, Attachment>> cases = new HashMap<>();
    private int storedBytes;
    private int storedAttachments;

    @Override
    public synchronized boolean insert(String tenantId, Metadata metadata, byte[] content) {
        if (storedAttachments >= MAX_ATTACHMENTS || content.length > MAX_STORED_BYTES - storedBytes) {
            return false;
        }
        var attachments = cases.computeIfAbsent(new CaseKey(tenantId, metadata.caseId()), key -> new LinkedHashMap<>());
        if (attachments.putIfAbsent(metadata.id(), new Attachment(metadata, content.clone())) != null) {
            throw new IllegalStateException("Duplicate evidence ID");
        }
        storedBytes += content.length;
        storedAttachments++;
        return true;
    }

    @Override
    public synchronized List<Metadata> list(String tenantId, UUID caseId) {
        return cases.getOrDefault(new CaseKey(tenantId, caseId), Map.of()).values().stream()
            .map(Attachment::metadata).toList();
    }

    @Override
    public synchronized Optional<Attachment> find(String tenantId, UUID caseId, UUID evidenceId) {
        var attachment = cases.getOrDefault(new CaseKey(tenantId, caseId), Map.of()).get(evidenceId);
        return Optional.ofNullable(attachment).map(found -> new Attachment(found.metadata(), found.content().clone()));
    }

    private record CaseKey(String tenantId, UUID caseId) {}
}
