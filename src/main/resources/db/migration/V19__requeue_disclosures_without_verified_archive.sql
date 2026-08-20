UPDATE disclosure d
SET document_status = 'PENDING',
    index_status = 'PENDING',
    updated_at = CURRENT_TIMESTAMP
FROM security s
JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
WHERE d.security_id = s.id
  AND s.active
  AND s.common_stock
  AND NOT EXISTS (
      SELECT 1
      FROM disclosure_archive archive
      WHERE archive.disclosure_id = d.id
        AND archive.archive_status = 'VERIFIED'
  );

UPDATE ingestion_job job
SET status = 'PENDING',
    attempts = 0,
    priority = CASE
        WHEN d.filed_date >= CURRENT_DATE - INTERVAL '1 year'
          OR (
              d.filed_date >= CURRENT_DATE - INTERVAL '5 years'
              AND d.title_ko ~ '(사업|반기|분기)보고서'
          ) THEN 10
        ELSE 20
    END,
    available_at = CURRENT_TIMESTAMP,
    locked_at = NULL,
    locked_by = NULL,
    last_error_code = 'ARCHIVE_REPAIR',
    updated_at = CURRENT_TIMESTAMP
FROM disclosure d
JOIN security s ON s.id = d.security_id
JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
WHERE job.job_type = 'DISCLOSURE_DOCUMENT'
  AND job.business_key = d.receipt_number
  AND s.active
  AND s.common_stock
  AND NOT EXISTS (
      SELECT 1
      FROM disclosure_archive archive
      WHERE archive.disclosure_id = d.id
        AND archive.archive_status = 'VERIFIED'
  );
