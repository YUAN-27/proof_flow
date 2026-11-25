package com.proofflow.business.cases;

import com.proofflow.business.cases.CaseService.AuditEntry;
import com.proofflow.business.cases.CaseService.CaseSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Storage for validated case snapshots and their audit events, partitioned by tenant and case ID. */
public interface CaseStore {
    /**
     * Inserts the snapshot and its creation audit atomically.
     * Returns false if the case already exists in that tenant.
     * Rejects a null audit before writing.
     */
    boolean insert(CaseSnapshot snapshot, AuditEntry audit);

    Optional<CaseSnapshot> find(String tenantId, UUID caseId);

    /**
     * Replaces the snapshot and appends its audit atomically if the case exists at the expected version.
     * Returns false without changing either value if the case is absent or the version differs.
     * Rejects a null audit before writing.
     */
    boolean updateIfVersionMatches(long expectedVersion, CaseSnapshot snapshot, AuditEntry audit);

    /** Returns an immutable history ordered by case version, or an empty list if the case is absent. */
    List<AuditEntry> auditTrail(String tenantId, UUID caseId);
}
