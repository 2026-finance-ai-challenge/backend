UPDATE ingestion_job
SET priority = 30,
    updated_at = CURRENT_TIMESTAMP
WHERE job_type = 'DISCLOSURE_DOCUMENT'
  AND status = 'PENDING'
  AND last_error_code IN ('PARSER_V3_REPAIR', 'DART_VIEWER_NETWORK_ERROR');
