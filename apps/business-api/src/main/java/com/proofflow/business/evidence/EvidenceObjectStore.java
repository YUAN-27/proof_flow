package com.proofflow.business.evidence;

interface EvidenceObjectStore {
    /** Stores bytes under a fresh key and returns only after storage acknowledges the write. */
    void put(String key, byte[] content);

    /** Reads exactly the expected number of bytes, failing for missing or inconsistent objects. */
    byte[] read(String key, long expectedSize);
}
