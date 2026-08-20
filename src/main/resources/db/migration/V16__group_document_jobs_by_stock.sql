ALTER TABLE ingestion_job
    ADD COLUMN stock_code VARCHAR(6);

UPDATE ingestion_job job
SET stock_code = security.stock_code
FROM disclosure
JOIN security ON security.id = disclosure.security_id
WHERE job.job_type = 'DISCLOSURE_DOCUMENT'
  AND job.business_key = disclosure.receipt_number;

DROP INDEX ingestion_job_document_priority_claim_idx;

CREATE INDEX ingestion_job_document_stock_claim_idx
    ON ingestion_job (
        (priority <> 0),
        stock_code,
        priority,
        attempts DESC,
        available_at,
        created_at
    )
    WHERE job_type = 'DISCLOSURE_DOCUMENT'
      AND status IN ('PENDING', 'PROCESSING');
