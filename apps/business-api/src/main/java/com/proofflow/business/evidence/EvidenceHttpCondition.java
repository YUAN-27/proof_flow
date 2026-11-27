package com.proofflow.business.evidence;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

final class EvidenceHttpCondition extends AnyNestedCondition {
    EvidenceHttpCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(name = "proofflow.cases.repository", havingValue = "memory", matchIfMissing = true)
    static class Memory {}

    @ConditionalOnProperty(name = "proofflow.evidence.persistence", havingValue = "true")
    static class Persistent {}
}
