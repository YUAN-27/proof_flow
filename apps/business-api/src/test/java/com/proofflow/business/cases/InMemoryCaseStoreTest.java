package com.proofflow.business.cases;

class InMemoryCaseStoreTest extends CaseStoreContractTest {
    @Override
    CaseStore newStore() {
        return new InMemoryCaseStore();
    }
}
