UPDATE disclosure
SET document_status = 'PENDING',
    index_status = 'PENDING',
    updated_at = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1
    FROM disclosure_document document
    WHERE document.disclosure_id = disclosure.id
      AND document.is_current
      AND document.parser_version <> 'opendart-html-v3'
);

UPDATE ingestion_job job
SET status = 'PENDING',
    attempts = 0,
    priority = 0,
    available_at = CURRENT_TIMESTAMP,
    locked_at = NULL,
    locked_by = NULL,
    last_error_code = 'PARSER_V3_REPAIR',
    updated_at = CURRENT_TIMESTAMP
FROM disclosure
WHERE job.job_type = 'DISCLOSURE_DOCUMENT'
  AND job.business_key = disclosure.receipt_number
  AND EXISTS (
      SELECT 1
      FROM disclosure_document document
      WHERE document.disclosure_id = disclosure.id
        AND document.is_current
        AND document.parser_version <> 'opendart-html-v3'
  );
