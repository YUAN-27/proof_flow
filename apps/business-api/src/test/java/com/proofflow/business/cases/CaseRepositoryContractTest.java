package com.proofflow.business.cases;

import com.proofflow.business.cases.CaseService.AuditEntry;
import com.proofflow.business.cases.CaseService.CaseSnapshot;
import com.proofflow.business.cases.CaseService.Operation;
import com.proofflow.business.cases.CaseService.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Extend this suite to exercise another CaseRepository implementation. */
abstract class CaseRepositoryContractTest {
    private static final Instant NOW = Instant.parse("2026-11-24T03:00:00Z");
    private final UUID caseId = UUID.randomUUID();
    private CaseRepository store;

    abstract CaseRepository newRepository();

    @BeforeEach
    void initializeRepository() {
        store = newRepository();
    }

    @Test
    void partitionsSnapshotsAndAuditsByTenantEvenWhenCaseIdsMatch() {
        CaseSnapshot first = snapshot("tenant-a", Status.OPEN, 1);
        CaseSnapshot second = snapshot("tenant-b", Status.OPEN, 1);
        AuditEntry firstAudit = audit(first, "actor-a", null);
        AuditEntry secondAudit = audit(second, "actor-b", null);

        assertThat(store.insert(first, firstAudit)).isTrue();
        assertThat(store.insert(second, secondAudit)).isTrue();
        assertThat(store.find("tenant-a", caseId)).contains(first);
        assertThat(store.find("tenant-b", caseId)).contains(second);
        assertThat(store.find("unknown-tenant", caseId)).isEmpty();
        assertThat(store.auditTrail("tenant-a", caseId)).containsExactly(firstAudit);
        assertThat(store.auditTrail("tenant-b", caseId)).containsExactly(secondAudit);
        assertThat(store.auditTrail("unknown-tenant", caseId)).isEmpty();
    }

    @Test
    void paginatesTenantQueueAndMatchesLiteralTitleOrId() {
        String tenant = "queue-" + UUID.randomUUID();
        var older = new CaseSnapshot(UUID.randomUUID(), tenant, "Serial % mismatch", Status.OPEN, 1);
        var newer = new CaseSnapshot(UUID.randomUUID(), tenant, "Serial number", Status.OPEN, 1);
        var other = new CaseSnapshot(UUID.randomUUID(), tenant + "-other", "Serial % mismatch", Status.OPEN, 1);
        assertThat(store.insert(older, audit(older, "intake", null))).isTrue();
        assertThat(store.insert(other, audit(other, "intake", null))).isTrue();
        assertThat(store.insert(newer, new AuditEntry(tenant, "intake", newer.id(), Operation.CREATED,
            NOW.plusSeconds(1), 1, null, Status.OPEN))).isTrue();

        assertThat(store.list(tenant, null, "", 1, 0)).containsExactly(newer);
        assertThat(store.list(tenant, null, "serial", 1, 1)).containsExactly(older);
        assertThat(store.list(tenant, Status.OPEN, "%", 10, 0)).containsExactly(older);
        assertThat(store.list(tenant, Status.CLOSED, "", 10, 0)).isEmpty();
        assertThat(store.list(tenant, null, older.id().toString(), 10, 0)).containsExactly(older);
    }

    @Test
    void duplicateInsertLeavesOriginalSnapshotAndAuditIntact() {
        CaseSnapshot original = snapshot("tenant-a", Status.OPEN, 1);
        CaseSnapshot duplicate = new CaseSnapshot(caseId, "tenant-a", "Different title", Status.OPEN, 1);
        AuditEntry originalAudit = audit(original, "intake", null);

        assertThat(store.insert(original, originalAudit)).isTrue();
        assertThat(store.insert(duplicate, audit(duplicate, "duplicate", null))).isFalse();
        assertThat(store.find("tenant-a", caseId)).contains(original);
        assertThat(store.auditTrail("tenant-a", caseId)).containsExactly(originalAudit);
    }

    @Test
    void commitsMatchingVersionsAndKeepsAnOrderedImmutableAuditHistory() {
        CaseSnapshot open = snapshot("tenant-a", Status.OPEN, 1);
        CaseSnapshot inReview = snapshot("tenant-a", Status.IN_REVIEW, 2);
        CaseSnapshot closed = snapshot("tenant-a", Status.CLOSED, 3);
        AuditEntry creation = audit(open, "intake", null);
        AuditEntry review = audit(inReview, "reviewer", Status.OPEN);
        AuditEntry closure = audit(closed, "reviewer", Status.IN_REVIEW);
        assertThat(store.insert(open, creation)).isTrue();
        List<AuditEntry> originalHistory = store.auditTrail("tenant-a", caseId);

        assertThat(store.updateIfVersionMatches(1, inReview, review)).isTrue();
        CaseSnapshot stale = snapshot("tenant-a", Status.IN_REVIEW, 2);
        assertThat(store.updateIfVersionMatches(1, stale, audit(stale, "stale", Status.OPEN))).isFalse();
        CaseSnapshot absent = snapshot("unknown-tenant", Status.IN_REVIEW, 2);
        assertThat(store.updateIfVersionMatches(1, absent, audit(absent, "other", Status.OPEN))).isFalse();
        assertThat(store.auditTrail("unknown-tenant", caseId)).isEmpty();
        assertThat(store.find("tenant-a", caseId)).contains(inReview);
        assertThat(store.auditTrail("tenant-a", caseId)).containsExactly(creation, review);

        assertThat(store.updateIfVersionMatches(2, closed, closure)).isTrue();
        assertThat(store.find("tenant-a", caseId)).contains(closed);
        assertThat(store.auditTrail("tenant-a", caseId)).containsExactly(creation, review, closure);
        assertThat(originalHistory).containsExactly(creation);
        assertThrows(UnsupportedOperationException.class, () -> originalHistory.add(review));
    }

    @Test
    void refusesToWriteASnapshotWithoutItsAudit() {
        CaseSnapshot open = snapshot("tenant-a", Status.OPEN, 1);
        CaseSnapshot inReview = snapshot("tenant-a", Status.IN_REVIEW, 2);
        AuditEntry creation = audit(open, "intake", null);

        assertThrows(NullPointerException.class, () -> store.insert(open, null));
        assertThat(store.find("tenant-a", caseId)).isEmpty();
        assertThat(store.auditTrail("tenant-a", caseId)).isEmpty();

        assertThat(store.insert(open, creation)).isTrue();
        assertThrows(NullPointerException.class, () -> store.updateIfVersionMatches(1, inReview, null));
        assertThat(store.find("tenant-a", caseId)).contains(open);
        assertThat(store.auditTrail("tenant-a", caseId)).containsExactly(creation);
    }

    @Test
    void racingWritesToTheSameVersionOnlyPersistTheWinningAudit() throws Exception {
        CaseSnapshot open = snapshot("tenant-a", Status.OPEN, 1);
        CaseSnapshot inReview = snapshot("tenant-a", Status.IN_REVIEW, 2);
        AuditEntry creation = audit(open, "intake", null);
        AuditEntry firstAudit = audit(inReview, "first", Status.OPEN);
        AuditEntry secondAudit = audit(inReview, "second", Status.OPEN);
        assertThat(store.insert(open, creation)).isTrue();
        CyclicBarrier start = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return store.updateIfVersionMatches(1, inReview, firstAudit);
            });
            var second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return store.updateIfVersionMatches(1, inReview, secondAudit);
            });
            boolean firstWon = first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(!firstWon);
            assertThat(store.find("tenant-a", caseId)).contains(inReview);
            assertThat(store.auditTrail("tenant-a", caseId))
                .containsExactly(creation, firstWon ? firstAudit : secondAudit);
        }
    }

    private CaseSnapshot snapshot(String tenant, Status status, long version) {
        return new CaseSnapshot(caseId, tenant, "Missing serial number", status, version);
    }

    private AuditEntry audit(CaseSnapshot snapshot, String actor, Status previous) {
        Operation operation = previous == null ? Operation.CREATED : Operation.STATUS_UPDATED;
        return new AuditEntry(snapshot.tenantId(), actor, snapshot.id(), operation, NOW,
            snapshot.version(), previous, snapshot.status());
    }
}
