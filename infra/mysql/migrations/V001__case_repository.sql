CREATE TABLE case_records (
    tenant_id VARCHAR(191) NOT NULL,
    case_id BINARY(16) NOT NULL,
    title TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, case_id),
    CONSTRAINT chk_case_records_title CHECK (CHAR_LENGTH(title) > 0),
    CONSTRAINT chk_case_records_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'CLOSED')),
    CONSTRAINT chk_case_records_version CHECK (version >= 1)
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE case_audit_entries (
    tenant_id VARCHAR(191) NOT NULL,
    case_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL,
    actor_id VARCHAR(191) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    previous_status VARCHAR(16),
    status VARCHAR(16) NOT NULL,
    PRIMARY KEY (tenant_id, case_id, version),
    CONSTRAINT fk_case_audit_record FOREIGN KEY (tenant_id, case_id)
        REFERENCES case_records (tenant_id, case_id) ON DELETE RESTRICT,
    CONSTRAINT chk_case_audit_status CHECK (
        status IN ('OPEN', 'IN_REVIEW', 'CLOSED')
        AND (previous_status IS NULL OR previous_status IN ('OPEN', 'IN_REVIEW', 'CLOSED'))
    ),
    CONSTRAINT chk_case_audit_operation CHECK (
        (operation = 'CREATED' AND version = 1 AND previous_status IS NULL AND status = 'OPEN')
        OR (operation = 'STATUS_UPDATED' AND version > 1 AND previous_status IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
