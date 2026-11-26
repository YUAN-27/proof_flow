CREATE TABLE case_evidence (
    tenant_id VARCHAR(191) NOT NULL,
    case_id BINARY(16) NOT NULL,
    evidence_id BINARY(16) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(32) NOT NULL,
    size_bytes INT UNSIGNED NOT NULL,
    uploaded_at DATETIME(6) NOT NULL,
    uploaded_by VARCHAR(191) NOT NULL,
    object_key VARBINARY(255) NOT NULL,
    PRIMARY KEY (tenant_id, case_id, evidence_id),
    UNIQUE KEY uq_case_evidence_object_key (object_key),
    KEY ix_case_evidence_upload_order (tenant_id, case_id, uploaded_at, evidence_id),
    CONSTRAINT fk_case_evidence_record FOREIGN KEY (tenant_id, case_id)
        REFERENCES case_records (tenant_id, case_id) ON DELETE RESTRICT,
    CONSTRAINT chk_case_evidence_filename CHECK (CHAR_LENGTH(TRIM(filename)) > 0),
    CONSTRAINT chk_case_evidence_content_type CHECK (content_type IN ('application/pdf', 'image/png', 'image/jpeg')),
    CONSTRAINT chk_case_evidence_size CHECK (size_bytes BETWEEN 1 AND 10485760),
    CONSTRAINT chk_case_evidence_uploader CHECK (CHAR_LENGTH(TRIM(uploaded_by)) > 0),
    CONSTRAINT chk_case_evidence_object_key CHECK (OCTET_LENGTH(object_key) > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
