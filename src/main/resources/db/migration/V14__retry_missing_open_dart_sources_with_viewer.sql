UPDATE disclosure
SET document_status = 'PENDING',
    index_status = 'PENDING',
    updated_at = CURRENT_TIMESTAMP
WHERE document_status = 'UNAVAILABLE'
  AND EXISTS (
      SELECT 1
      FROM ingestion_job
      WHERE ingestion_job.job_type = 'DISCLOSURE_DOCUMENT'
        AND ingestion_job.business_key = disclosure.receipt_number
        AND ingestion_job.last_error_code = 'STATUS_014'
  );

UPDATE ingestion_job
SET status = 'PENDING',
    attempts = 0,
    priority = 10,
    available_at = CURRENT_TIMESTAMP,
    locked_at = NULL,
    locked_by = NULL,
    last_error_code = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE job_type = 'DISCLOSURE_DOCUMENT'
  AND last_error_code = 'STATUS_014';
