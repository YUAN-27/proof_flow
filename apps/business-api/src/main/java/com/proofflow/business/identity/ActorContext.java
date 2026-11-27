package com.proofflow.business.identity;

/** Tenant and actor identifiers supplied by a trusted caller. */
public record ActorContext(String tenantId, String actorId) {
    public static final String REQUEST_ATTRIBUTE = "caseActor";

    public ActorContext {
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(actorId, "actorId");
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
