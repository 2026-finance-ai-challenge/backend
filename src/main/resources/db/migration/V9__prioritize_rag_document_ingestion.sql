ALTER TABLE ingestion_job
    ADD COLUMN priority SMALLINT NOT NULL DEFAULT 100,
    ADD CONSTRAINT ingestion_job_priority_range CHECK (priority BETWEEN 0 AND 100);

UPDATE ingestion_job job
SET priority = CASE
    WHEN job.last_error_code = 'ON_DEMAND' THEN 0
    WHEN disclosure.filed_date >= CURRENT_DATE - INTERVAL '1 year'
      OR (
          disclosure.filed_date >= CURRENT_DATE - INTERVAL '5 years'
          AND disclosure.title_ko ~ '(사업|반기|분기)보고서'
      ) THEN 10
    ELSE 20
END
FROM disclosure
JOIN security ON security.id = disclosure.security_id
JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
WHERE job.job_type = 'DISCLOSURE_DOCUMENT'
  AND job.business_key = disclosure.receipt_number
  AND security.active
  AND security.common_stock;

UPDATE disclosure
SET document_status = 'UNAVAILABLE',
    index_status = 'UNAVAILABLE',
    updated_at = CURRENT_TIMESTAMP
FROM ingestion_job job
WHERE job.job_type = 'DISCLOSURE_DOCUMENT'
  AND job.business_key = disclosure.receipt_number
  AND job.status IN ('PENDING', 'PROCESSING')
  AND job.last_error_code = 'STATUS_014';

UPDATE ingestion_job
SET status = 'COMPLETED',
    locked_at = NULL,
    locked_by = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE job_type = 'DISCLOSURE_DOCUMENT'
  AND status IN ('PENDING', 'PROCESSING')
  AND last_error_code = 'STATUS_014';

CREATE INDEX ingestion_job_document_priority_claim_idx
    ON ingestion_job (priority, attempts DESC, available_at, created_at)
    WHERE job_type = 'DISCLOSURE_DOCUMENT'
      AND status IN ('PENDING', 'PROCESSING');
