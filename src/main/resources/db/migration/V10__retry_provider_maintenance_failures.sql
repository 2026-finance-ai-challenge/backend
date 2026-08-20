UPDATE disclosure
SET document_status = 'PENDING',
    index_status = 'PENDING',
    updated_at = CURRENT_TIMESTAMP
FROM ingestion_job job
WHERE job.job_type = 'DISCLOSURE_DOCUMENT'
  AND job.business_key = disclosure.receipt_number
  AND job.status = 'FAILED'
  AND job.last_error_code = 'STATUS_800';

UPDATE ingestion_job
SET status = 'PENDING',
    attempts = 0,
    available_at = CURRENT_TIMESTAMP,
    locked_at = NULL,
    locked_by = NULL,
    last_error_code = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE job_type = 'DISCLOSURE_DOCUMENT'
  AND status = 'FAILED'
  AND last_error_code = 'STATUS_800';
