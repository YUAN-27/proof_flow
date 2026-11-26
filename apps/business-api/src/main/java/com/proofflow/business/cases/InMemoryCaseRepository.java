package com.proofflow.business.cases;

import com.proofflow.business.cases.CaseService.AuditEntry;
import com.proofflow.business.cases.CaseService.CaseSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
class InMemoryCaseRepository implements CaseRepository {
    private final Map<CaseKey, CaseHistory> cases = new HashMap<>();

    @Override
    public synchronized boolean insert(CaseSnapshot snapshot, AuditEntry audit) {
        CaseKey key = new CaseKey(snapshot.tenantId(), snapshot.id());
        CaseHistory created = new CaseHistory(snapshot, List.of(audit));
        return cases.putIfAbsent(key, created) == null;
    }

    @Override
    public synchronized Optional<CaseSnapshot> find(String tenantId, UUID caseId) {
        CaseHistory history = cases.get(new CaseKey(tenantId, caseId));
        return history == null ? Optional.empty() : Optional.of(history.snapshot());
    }

    @Override
    public synchronized boolean updateIfVersionMatches(long expectedVersion, CaseSnapshot snapshot, AuditEntry audit) {
        Objects.requireNonNull(audit, "audit");
        CaseKey key = new CaseKey(snapshot.tenantId(), snapshot.id());
        CaseHistory current = cases.get(key);
        if (current == null || current.snapshot().version() != expectedVersion) {
            return false;
        }

        List<AuditEntry> history = new ArrayList<>(current.audit());
        history.add(audit);
        cases.put(key, new CaseHistory(snapshot, List.copyOf(history)));
        return true;
    }

    @Override
    public synchronized List<AuditEntry> auditTrail(String tenantId, UUID caseId) {
        CaseHistory history = cases.get(new CaseKey(tenantId, caseId));
        return history == null ? List.of() : history.audit();
    }

    private record CaseKey(String tenantId, UUID caseId) {}

    private record CaseHistory(CaseSnapshot snapshot, List<AuditEntry> audit) {}
}
