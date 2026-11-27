DELIMITER //

CREATE PROCEDURE verify_case_evidence_schema()
BEGIN
    DECLARE v_case BINARY(16) DEFAULT UUID_TO_BIN('10000000-0000-0000-0000-000000000001');
    DECLARE v_first BINARY(16) DEFAULT UUID_TO_BIN('20000000-0000-0000-0000-000000000001');
    DECLARE v_second BINARY(16) DEFAULT UUID_TO_BIN('20000000-0000-0000-0000-000000000002');
    DECLARE v_other BINARY(16) DEFAULT UUID_TO_BIN('20000000-0000-0000-0000-000000000003');
    DECLARE v_rejected BINARY(16) DEFAULT UUID_TO_BIN('20000000-0000-0000-0000-000000000004');
    DECLARE v_now DATETIME(6) DEFAULT '2026-11-26 00:00:00.123456';
    DECLARE v_error BOOLEAN DEFAULT FALSE;

    START TRANSACTION;
    INSERT INTO case_records (tenant_id, case_id, title, status, version)
    VALUES ('evidence-tenant-a', v_case, 'Case A', 'OPEN', 1),
           ('evidence-tenant-b', v_case, 'Case B', 'OPEN', 1);

    INSERT INTO case_evidence
        (tenant_id, case_id, evidence_id, filename, content_type, size_bytes, uploaded_at, uploaded_by, object_key)
    VALUES ('evidence-tenant-a', v_case, v_second, 'image.png', 'image/png', 24, v_now, 'actor-a', 'evidence/second'),
           ('evidence-tenant-a', v_case, v_first, 'receipt.pdf', 'application/pdf', 12, v_now, 'actor-a', 'evidence/first'),
           ('evidence-tenant-b', v_case, v_other, 'other.jpg', 'image/jpeg', 36, v_now, 'actor-b', 'evidence/other');

    IF NOT ((SELECT GROUP_CONCAT(filename ORDER BY uploaded_at, evidence_id)
        FROM case_evidence WHERE tenant_id = 'evidence-tenant-a' AND case_id = v_case) <=> 'receipt.pdf,image.png') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Evidence listing ignored tenant or upload ordering';
    END IF;
    IF EXISTS (SELECT 1 FROM case_evidence
               WHERE tenant_id = 'evidence-tenant-b' AND case_id = v_case AND evidence_id = v_first) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Tenant B can match tenant A attachment';
    END IF;

    BEGIN
        DECLARE CONTINUE HANDLER FOR 1452 SET v_error = TRUE;
        INSERT INTO case_evidence VALUES
            ('evidence-tenant-missing', v_case, v_rejected, 'receipt.pdf', 'application/pdf', 12, v_now, 'actor-a', 'evidence/missing');
    END;
    IF NOT v_error THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Attachment can reference a case outside its tenant';
    END IF;

    SET v_error = FALSE;
    BEGIN
        DECLARE CONTINUE HANDLER FOR 1062 SET v_error = TRUE;
        INSERT INTO case_evidence VALUES
            ('evidence-tenant-b', v_case, v_rejected, 'receipt.pdf', 'application/pdf', 12, v_now, 'actor-b', 'evidence/first');
    END;
    IF NOT v_error THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'An object key can be reused by another attachment';
    END IF;

    SET v_error = FALSE;
    BEGIN
        DECLARE CONTINUE HANDLER FOR 3819 SET v_error = TRUE;
        INSERT INTO case_evidence VALUES
            ('evidence-tenant-a', v_case, v_rejected, 'too-big.pdf', 'application/pdf', 10485761, v_now, 'actor-a', 'evidence/rejected');
    END;
    IF NOT v_error OR EXISTS (SELECT 1 FROM case_evidence WHERE evidence_id = v_rejected) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rejected upload left attachment metadata';
    END IF;

    ROLLBACK;
    SELECT 'MySQL evidence schema checks passed' AS result;
END//

DELIMITER ;

CALL verify_case_evidence_schema();
DROP PROCEDURE verify_case_evidence_schema;
