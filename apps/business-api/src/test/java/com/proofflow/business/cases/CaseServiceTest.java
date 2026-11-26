package com.proofflow.business.cases;

import com.proofflow.business.cases.CaseService.AuditEntry;
import com.proofflow.business.cases.CaseService.CaseSnapshot;
import com.proofflow.business.cases.CaseService.Operation;
import com.proofflow.business.cases.CaseService.Status;
import com.proofflow.business.cases.CaseService.UpdateFailure;
import com.proofflow.business.cases.CaseService.UpdateRejected;
import com.proofflow.business.identity.ActorContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaseServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-24T03:00:00Z");
    private static final ActorContext INTAKE = new ActorContext("tenant-a", "intake");
    private static final ActorContext REVIEWER = new ActorContext("tenant-a", "reviewer");
    private static final ActorContext OTHER_TENANT = new ActorContext("tenant-b", "reviewer");

    private final CaseService service =
        new CaseService(new InMemoryCaseRepository(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createsAnOpenCaseAndRecordsWhoCreatedIt() {
        CaseSnapshot created = service.create(INTAKE, "Missing serial number");

        assertThat(created.id()).isNotNull();
        assertThat(created.tenantId()).isEqualTo("tenant-a");
        assertThat(created.title()).isEqualTo("Missing serial number");
        assertThat(created.status()).isEqualTo(Status.OPEN);
        assertThat(created.version()).isEqualTo(1L);
        assertThat(service.find(REVIEWER, created.id())).contains(created);
        assertThat(service.auditTrail(REVIEWER, created.id())).containsExactly(
            new AuditEntry("tenant-a", "intake", created.id(), Operation.CREATED, NOW, 1, null, Status.OPEN)
        );
    }

    @Test
    void treatsAnotherTenantsCaseAsMissing() {
        CaseSnapshot created = service.create(INTAKE, "Missing serial number");
        UUID unknown = UUID.randomUUID();

        assertThat(service.find(OTHER_TENANT, created.id())).isEqualTo(service.find(OTHER_TENANT, unknown))
            .isEmpty();
        assertThat(service.auditTrail(OTHER_TENANT, created.id())).isEmpty();
        assertThat(rejection(OTHER_TENANT, created.id(), 1, Status.IN_REVIEW)).isEqualTo(UpdateFailure.NOT_FOUND);
        assertThat(rejection(OTHER_TENANT, unknown, 1, Status.IN_REVIEW)).isEqualTo(UpdateFailure.NOT_FOUND);
        assertThat(service.find(INTAKE, created.id())).contains(created);
        assertThat(service.auditTrail(INTAKE, created.id())).hasSize(1);
    }

    @Test
    void advancesTheLifecycleAndAddsAuditEntriesInOrder() {
        CaseSnapshot created = service.create(INTAKE, "Missing serial number");
        List<AuditEntry> originalAudit = service.auditTrail(INTAKE, created.id());

        CaseSnapshot inReview = service.updateStatus(REVIEWER, created.id(), 1, Status.IN_REVIEW);
        CaseSnapshot closed = service.updateStatus(REVIEWER, created.id(), 2, Status.CLOSED);

        assertThat(inReview.status()).isEqualTo(Status.IN_REVIEW);
        assertThat(inReview.version()).isEqualTo(2L);
        assertThat(closed.status()).isEqualTo(Status.CLOSED);
        assertThat(closed.version()).isEqualTo(3L);
        assertThat(service.find(INTAKE, created.id())).contains(closed);
        assertThat(originalAudit).hasSize(1);
        assertThat(service.auditTrail(INTAKE, created.id())).containsExactly(
            originalAudit.getFirst(),
            new AuditEntry("tenant-a", "reviewer", created.id(), Operation.STATUS_UPDATED, NOW, 2,
                Status.OPEN, Status.IN_REVIEW),
            new AuditEntry("tenant-a", "reviewer", created.id(), Operation.STATUS_UPDATED, NOW, 3,
                Status.IN_REVIEW, Status.CLOSED)
        );
    }

    @Test
    void rejectsStaleVersionsAndIllegalTransitionsWithoutRecordingThem() {
        CaseSnapshot created = service.create(INTAKE, "Missing serial number");
        UUID id = created.id();

        assertThat(rejection(REVIEWER, id, 1, Status.OPEN)).isEqualTo(UpdateFailure.INVALID_STATUS_TRANSITION);
        assertThat(rejection(REVIEWER, id, 1, Status.CLOSED)).isEqualTo(UpdateFailure.INVALID_STATUS_TRANSITION);
        assertThat(service.find(INTAKE, id)).contains(created);
        assertThat(service.auditTrail(INTAKE, id)).hasSize(1);

        CaseSnapshot inReview = service.updateStatus(REVIEWER, id, 1, Status.IN_REVIEW);
        assertThat(rejection(REVIEWER, id, 1, Status.CLOSED)).isEqualTo(UpdateFailure.VERSION_CONFLICT);
        assertThat(service.find(INTAKE, id)).contains(inReview);
        assertThat(service.auditTrail(INTAKE, id)).hasSize(2);

        CaseSnapshot closed = service.updateStatus(REVIEWER, id, 2, Status.CLOSED);
        assertThat(rejection(REVIEWER, id, 3, Status.IN_REVIEW)).isEqualTo(UpdateFailure.INVALID_STATUS_TRANSITION);
        assertThat(service.find(INTAKE, id)).contains(closed);
        assertThat(service.auditTrail(INTAKE, id)).hasSize(3);
    }

    @Test
    void rejectsInvalidInputsBeforeWriting() {
        assertThrows(IllegalArgumentException.class, () -> new ActorContext(" ", "reviewer"));
        assertThrows(IllegalArgumentException.class, () -> new ActorContext("tenant-a", null));
        assertThrows(IllegalArgumentException.class, () -> service.create(INTAKE, " "));
        CaseSnapshot created = service.create(INTAKE, "Missing serial number");
        assertThrows(IllegalArgumentException.class,
            () -> service.updateStatus(REVIEWER, created.id(), 0, Status.IN_REVIEW));

        assertThat(service.find(INTAKE, created.id())).contains(created);
        assertThat(service.auditTrail(INTAKE, created.id())).hasSize(1);
    }

    @Test
    void onlyOneConcurrentUpdateCanUseTheSameVersion() throws Exception {
        CaseSnapshot created = service.create(INTAKE, "Missing serial number");
        UUID id = created.id();
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Boolean> attempt = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                service.updateStatus(REVIEWER, id, 1, Status.IN_REVIEW);
                return true;
            } catch (UpdateRejected rejected) {
                assertThat(rejected.reason()).isEqualTo(UpdateFailure.VERSION_CONFLICT);
                return false;
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }

        assertThat(service.find(INTAKE, id).orElseThrow().version()).isEqualTo(2L);
        assertThat(service.auditTrail(INTAKE, id)).extracting(AuditEntry::operation)
            .containsExactly(Operation.CREATED, Operation.STATUS_UPDATED);
    }

    private UpdateFailure rejection(ActorContext actor, UUID id, long version, Status next) {
        return assertThrows(UpdateRejected.class, () -> service.updateStatus(actor, id, version, next)).reason();
    }
}
