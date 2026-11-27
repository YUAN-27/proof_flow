CREATE TABLE staff_accounts (
    username VARCHAR(64) NOT NULL,
    password_hash CHAR(60) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(191) NOT NULL,
    actor_id VARCHAR(191) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (username),
    UNIQUE KEY uq_staff_actor (tenant_id, actor_id),
    CONSTRAINT chk_staff_username CHECK (REGEXP_LIKE(username, '^[a-z0-9][a-z0-9._-]{0,63}$', 'c')),
    CONSTRAINT chk_staff_tenant CHECK (CHAR_LENGTH(TRIM(tenant_id)) > 0),
    CONSTRAINT chk_staff_actor CHECK (CHAR_LENGTH(TRIM(actor_id)) > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
