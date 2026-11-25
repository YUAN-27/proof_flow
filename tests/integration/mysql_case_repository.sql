-- Run only against an empty, disposable schema after V001__case_repository.sql.
DELIMITER //

CREATE PROCEDURE verify_case_repository_schema()
BEGIN
    DECLARE v_case_id BINARY(16) DEFAULT UUID_TO_BIN('00000000-0000-0000-0000-000000000001');
    DECLARE v_duplicate_rejected BOOLEAN DEFAULT FALSE;
    DECLARE v_foreign_audit_rejected BOOLEAN DEFAULT FALSE;
    DECLARE v_rolled_back BOOLEAN DEFAULT FALSE;

    START TRANSACTION;
    INSERT INTO case_records (tenant_id, case_id, title, status, version)
    VALUES ('tenant-a', v_case_id, 'First case', 'OPEN', 1),
           ('Tenant-A', v_case_id, 'Second case', 'OPEN', 1),
           ('tenant-a ', v_case_id, 'Third case', 'OPEN', 1);
    INSERT INTO case_audit_entries
        (tenant_id, case_id, version, actor_id, operation, occurred_at, previous_status, status)
    VALUES ('tenant-a', v_case_id, 1, 'intake', 'CREATED', UTC_TIMESTAMP(6), NULL, 'OPEN'),
           ('Tenant-A', v_case_id, 1, 'intake', 'CREATED', UTC_TIMESTAMP(6), NULL, 'OPEN'),
           ('tenant-a ', v_case_id, 1, 'intake', 'CREATED', UTC_TIMESTAMP(6), NULL, 'OPEN');
    COMMIT;

    BEGIN
        DECLARE CONTINUE HANDLER FOR 1062 SET v_duplicate_rejected = TRUE;
        INSERT INTO case_records (tenant_id, case_id, title, status, version)
        VALUES ('tenant-a', v_case_id, 'Duplicate', 'OPEN', 1);
    END;
    IF NOT v_duplicate_rejected THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Duplicate case key was accepted';
    END IF;

    BEGIN
        DECLARE CONTINUE HANDLER FOR 1452 SET v_foreign_audit_rejected = TRUE;
        INSERT INTO case_audit_entries
            (tenant_id, case_id, version, actor_id, operation, occurred_at, previous_status, status)
        VALUES ('foreign-tenant', v_case_id, 1, 'intake', 'CREATED', UTC_TIMESTAMP(6), NULL, 'OPEN');
    END;
    IF NOT v_foreign_audit_rejected THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Audit accepted a case from another tenant';
    END IF;

    START TRANSACTION;
    UPDATE case_records SET status = 'IN_REVIEW', version = 2
    WHERE tenant_id = 'tenant-a' AND case_id = v_case_id AND version = 1;
    IF ROW_COUNT() <> 1 THEN
        ROLLBACK;
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Version 1 did not match exactly one case';
    END IF;
    INSERT INTO case_audit_entries
        (tenant_id, case_id, version, actor_id, operation, occurred_at, previous_status, status)
    VALUES ('tenant-a', v_case_id, 2, 'reviewer', 'STATUS_UPDATED', UTC_TIMESTAMP(6), 'OPEN', 'IN_REVIEW');
    COMMIT;

    UPDATE case_records SET status = 'IN_REVIEW', version = 2
    WHERE tenant_id = 'tenant-a' AND case_id = v_case_id AND version = 1;
    IF ROW_COUNT() <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A stale version updated the case';
    END IF;

    BEGIN
        DECLARE EXIT HANDLER FOR 1048
        BEGIN
            ROLLBACK;
            SET v_rolled_back = TRUE;
        END;
        START TRANSACTION;
        UPDATE case_records SET status = 'CLOSED', version = 3
        WHERE tenant_id = 'tenant-a' AND case_id = v_case_id AND version = 2;
        IF ROW_COUNT() <> 1 THEN
            ROLLBACK;
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rollback fixture could not update the case';
        END IF;
        INSERT INTO case_audit_entries
            (tenant_id, case_id, version, actor_id, operation, occurred_at, previous_status, status)
        VALUES ('tenant-a', v_case_id, 3, NULL, 'STATUS_UPDATED', UTC_TIMESTAMP(6), 'IN_REVIEW', 'CLOSED');
        COMMIT;
    END;

    IF NOT v_rolled_back OR NOT EXISTS (
        SELECT 1 FROM case_records
        WHERE tenant_id = 'tenant-a' AND case_id = v_case_id AND version = 2 AND status = 'IN_REVIEW'
    ) OR (
        SELECT COUNT(*) FROM case_audit_entries WHERE tenant_id = 'tenant-a' AND case_id = v_case_id
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Audit failure did not preserve snapshot and history';
    END IF;

    IF (
        SELECT COUNT(*) FROM case_records
        WHERE tenant_id IN ('Tenant-A', 'tenant-a ') AND case_id = v_case_id AND version = 1 AND status = 'OPEN'
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Update leaked across tenant keys';
    END IF;

    SELECT 'MySQL case schema checks passed' AS result;
END//

DELIMITER ;
CALL verify_case_repository_schema();
DROP PROCEDURE verify_case_repository_schema;
