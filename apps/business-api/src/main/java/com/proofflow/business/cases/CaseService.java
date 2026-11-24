package com.proofflow.business.cases;

import com.proofflow.business.identity.ActorContext;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** In-process case lifecycle and audit history. Callers must establish actor identity. */
@Service
public class CaseService {
    private final Clock clock;
    private final Map<CaseKey, CaseHistory> cases = new HashMap<>();

    public CaseService() {
        this(Clock.systemUTC());
    }

    CaseService(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized CaseSnapshot create(ActorContext actor, String title) {
        Objects.requireNonNull(actor, "actor");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }

        CaseKey key;
        do {
            key = new CaseKey(actor.tenantId(), UUID.randomUUID());
        } while (cases.containsKey(key));

        CaseSnapshot created = new CaseSnapshot(key.caseId(), key.tenantId(), title, Status.OPEN, 1);
        AuditEntry entry = auditEntry(actor, created, Operation.CREATED, null);
        cases.put(key, new CaseHistory(created, List.of(entry)));
        return created;
    }

    public synchronized Optional<CaseSnapshot> find(ActorContext actor, UUID caseId) {
        CaseHistory history = cases.get(keyFor(actor, caseId));
        return history == null ? Optional.empty() : Optional.of(history.snapshot());
    }

    public synchronized CaseSnapshot updateStatus(
        ActorContext actor, UUID caseId, long expectedVersion, Status nextStatus
    ) {
        CaseKey key = keyFor(actor, caseId);
        Objects.requireNonNull(nextStatus, "nextStatus");
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }

        CaseHistory history = cases.get(key);
        if (history == null) {
            throw new UpdateRejected(UpdateFailure.NOT_FOUND);
        }

        CaseSnapshot current = history.snapshot();
        if (current.version() != expectedVersion) {
            throw new UpdateRejected(UpdateFailure.VERSION_CONFLICT);
        }
        if (!current.status().canTransitionTo(nextStatus)) {
            throw new UpdateRejected(UpdateFailure.INVALID_STATUS_TRANSITION);
        }

        CaseSnapshot updated = new CaseSnapshot(
            current.id(), current.tenantId(), current.title(), nextStatus, current.version() + 1
        );
        List<AuditEntry> audit = new ArrayList<>(history.audit());
        audit.add(auditEntry(actor, updated, Operation.STATUS_UPDATED, current.status()));
        cases.put(key, new CaseHistory(updated, List.copyOf(audit)));
        return updated;
    }

    public synchronized List<AuditEntry> auditTrail(ActorContext actor, UUID caseId) {
        CaseHistory history = cases.get(keyFor(actor, caseId));
        return history == null ? List.of() : history.audit();
    }

    private static CaseKey keyFor(ActorContext actor, UUID caseId) {
        Objects.requireNonNull(actor, "actor");
        return new CaseKey(actor.tenantId(), Objects.requireNonNull(caseId, "caseId"));
    }

    private AuditEntry auditEntry(ActorContext actor, CaseSnapshot snapshot, Operation operation, Status previous) {
        return new AuditEntry(
            snapshot.tenantId(), actor.actorId(), snapshot.id(), operation, clock.instant(),
            snapshot.version(), previous, snapshot.status()
        );
    }

    public enum Status {
        OPEN, IN_REVIEW, CLOSED;

        private boolean canTransitionTo(Status next) {
            return switch (this) {
                case OPEN -> next == IN_REVIEW;
                case IN_REVIEW -> next == CLOSED;
                case CLOSED -> false;
            };
        }
    }

    public enum Operation {
        CREATED, STATUS_UPDATED
    }

    public enum UpdateFailure {
        NOT_FOUND, VERSION_CONFLICT, INVALID_STATUS_TRANSITION
    }

    public static final class UpdateRejected extends RuntimeException {
        private final UpdateFailure reason;

        private UpdateRejected(UpdateFailure reason) {
            super(reason.name());
            this.reason = reason;
        }

        public UpdateFailure reason() {
            return reason;
        }
    }

    public record CaseSnapshot(UUID id, String tenantId, String title, Status status, long version) {}

    public record AuditEntry(
        String tenantId, String actorId, UUID caseId, Operation operation, Instant occurredAt,
        long version, Status previousStatus, Status status
    ) {}

    private record CaseKey(String tenantId, UUID caseId) {}

    private record CaseHistory(CaseSnapshot snapshot, List<AuditEntry> audit) {}
}
