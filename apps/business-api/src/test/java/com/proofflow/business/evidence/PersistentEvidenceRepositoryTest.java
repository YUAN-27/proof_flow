package com.proofflow.business.evidence;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PersistentEvidenceRepositoryTest {
    private static final String TENANT = "tenant-a";
    private static final byte[] CONTENT = {1, 2, 3};
    private final MySqlEvidenceMetadataRepository metadata = mock(MySqlEvidenceMetadataRepository.class);
    private final EvidenceObjectStore objects = mock(EvidenceObjectStore.class);
    private final PersistentEvidenceRepository repository = new PersistentEvidenceRepository(metadata, objects);
    private final EvidenceRepository.Metadata attachment = new EvidenceRepository.Metadata(
        UUID.randomUUID(), UUID.randomUUID(), "proof.pdf", "application/pdf", CONTENT.length,
        Instant.parse("2026-11-27T02:00:00Z"), "operator");

    @Test
    void writesObjectBeforePublishingMetadataWithAnOpaqueKey() {
        assertThat(repository.insert(TENANT, attachment, CONTENT)).isTrue();

        var key = ArgumentCaptor.forClass(String.class);
        var order = inOrder(objects, metadata);
        order.verify(objects).put(key.capture(), eq(CONTENT));
        assertThat(key.getValue()).matches("case-evidence/[0-9a-f-]{36}");
        order.verify(metadata).insert(TENANT, entry(key.getValue()));
    }

    @Test
    void storageFailureDoesNotPublishMetadata() {
        var error = new IllegalStateException("storage unavailable");
        doThrow(error).when(objects).put(anyString(), eq(CONTENT));

        assertThatThrownBy(() -> repository.insert(TENANT, attachment, CONTENT)).isSameAs(error);
        verifyNoInteractions(metadata);
    }

    @Test
    void sqlFailureIsReportedAfterObjectWriteWithoutRetryingPublication() {
        var error = new IllegalStateException("commit result unknown");
        doThrow(error).when(metadata).insert(eq(TENANT), any());

        assertThatThrownBy(() -> repository.insert(TENANT, attachment, CONTENT)).isSameAs(error);
        var order = inOrder(objects, metadata);
        order.verify(objects).put(anyString(), eq(CONTENT));
        order.verify(metadata).insert(eq(TENANT), any());
        order.verifyNoMoreInteractions();
    }

    @Test
    void rejectsInconsistentSizeBeforeTouchingStorage() {
        assertThatThrownBy(() -> repository.insert(TENANT, attachment, new byte[1]))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(objects, metadata);
    }

    @Test
    void listAndDownloadUseTenantAndCaseMetadataBeforeReadingContent() {
        var row = entry("server-generated-key");
        when(metadata.list(TENANT, attachment.caseId())).thenReturn(List.of(row));
        when(metadata.find(TENANT, attachment.caseId(), attachment.id())).thenReturn(Optional.of(row));
        when(objects.read(row.objectKey(), CONTENT.length)).thenReturn(CONTENT);

        assertThat(repository.list(TENANT, attachment.caseId())).containsExactly(attachment);
        assertThat(repository.find("other-tenant", attachment.caseId(), attachment.id())).isEmpty();
        assertThat(repository.find(TENANT, UUID.randomUUID(), attachment.id())).isEmpty();
        verifyNoInteractions(objects);

        var found = repository.find(TENANT, attachment.caseId(), attachment.id()).orElseThrow();
        assertThat(found.metadata()).isEqualTo(attachment);
        assertThat(found.content()).containsExactly(CONTENT);
    }

    private MySqlEvidenceMetadataRepository.Entry entry(String key) {
        return new MySqlEvidenceMetadataRepository.Entry(attachment.id(), attachment.caseId(), attachment.filename(),
            attachment.contentType(), attachment.sizeBytes(), attachment.uploadedAt(), attachment.uploadedBy(), key);
    }
}
