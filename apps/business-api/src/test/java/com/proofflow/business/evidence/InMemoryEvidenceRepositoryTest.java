package com.proofflow.business.evidence;

import com.proofflow.business.evidence.EvidenceRepository.Metadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEvidenceRepositoryTest {
    @Test
    void doesNotPublishAnAttachmentWhenStorageIsFull() {
        var repository = new InMemoryEvidenceRepository();
        var caseId = UUID.randomUUID();
        byte[] content = {1, 2, 3};

        for (int index = 0; index < 256; index++) {
            assertThat(repository.insert("tenant", metadata(caseId), content)).isTrue();
        }

        var rejected = metadata(caseId);
        assertThat(repository.insert("tenant", rejected, content)).isFalse();
        assertThat(repository.find("tenant", caseId, rejected.id())).isEmpty();
        assertThat(repository.list("tenant", caseId)).hasSize(256);
    }

    private static Metadata metadata(UUID caseId) {
        return new Metadata(UUID.randomUUID(), caseId, "receipt.pdf", "application/pdf", 3, Instant.EPOCH, "actor");
    }
}
