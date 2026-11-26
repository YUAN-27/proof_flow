package com.proofflow.business.cases;

import com.proofflow.business.identity.ActorContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Case lifecycle and audit history. Callers must establish actor identity. */
@Service
public class CaseService {
    private final CaseRepository repository;
    private final Clock clock;

    @Autowired
    public CaseService(CaseRepository repository) {
        this(repository, Clock.systemUTC());
    }

    CaseService(CaseRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    public CaseSnapshot create(ActorContext actor, String title) {
        Objects.requireNonNull(actor, "actor");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }

        CaseSnapshot created;
        AuditEntry entry;
        do {
            created = new CaseSnapshot(UUID.randomUUID(), actor.tenantId(), title, Status.OPEN, 1);
            entry = auditEntry(actor, created, Operation.CREATED, null);
        } while (!repository.insert(created, entry));
        return created;
    }

    public Optional<CaseSnapshot> find(ActorContext actor, UUID caseId) {
        return repository.find(
            Objects.requireNonNull(actor, "actor").tenantId(), Objects.requireNonNull(caseId, "caseId")
        );
    }

    public CaseSnapshot updateStatus(
        ActorContext actor, UUID caseId, long expectedVersion, Status nextStatus
    ) {
        String tenantId = Objects.requireNonNull(actor, "actor").tenantId();
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(nextStatus, "nextStatus");
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }

        CaseSnapshot current = repository.find(tenantId, caseId)
            .orElseThrow(() -> new UpdateRejected(UpdateFailure.NOT_FOUND));
        if (current.version() != expectedVersion) {
            throw new UpdateRejected(UpdateFailure.VERSION_CONFLICT);
        }
        if (!current.status().canTransitionTo(nextStatus)) {
            throw new UpdateRejected(UpdateFailure.INVALID_STATUS_TRANSITION);
        }

        CaseSnapshot updated = new CaseSnapshot(
            current.id(), current.tenantId(), current.title(), nextStatus, current.version() + 1
        );
        AuditEntry entry = auditEntry(actor, updated, Operation.STATUS_UPDATED, current.status());
        if (!repository.updateIfVersionMatches(expectedVersion, updated, entry)) {
            throw new UpdateRejected(UpdateFailure.VERSION_CONFLICT);
        }
        return updated;
    }

    public List<AuditEntry> auditTrail(ActorContext actor, UUID caseId) {
        return repository.auditTrail(
            Objects.requireNonNull(actor, "actor").tenantId(), Objects.requireNonNull(caseId, "caseId")
        );
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
}
