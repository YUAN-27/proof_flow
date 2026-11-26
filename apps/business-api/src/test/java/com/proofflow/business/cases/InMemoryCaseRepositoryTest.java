package com.proofflow.business.cases;

class InMemoryCaseRepositoryTest extends CaseRepositoryContractTest {
    @Override
    CaseRepository newRepository() {
        return new InMemoryCaseRepository();
    }
}
