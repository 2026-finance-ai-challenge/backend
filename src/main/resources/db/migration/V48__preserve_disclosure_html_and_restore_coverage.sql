-- 종목별 최신 공시를 번갈아 처리해 신규 편입 종목도 빠르게 원문을 확보한다.
WITH ranked AS (
    SELECT d.receipt_number,
           (row_number() OVER (
               PARTITION BY s.stock_code
               ORDER BY d.filed_date DESC, d.receipt_number DESC
           ) - 1)::integer AS stock_priority
    FROM disclosure d
    JOIN security s ON s.id = d.security_id
    JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
    WHERE s.active AND s.common_stock
), selected AS (
    SELECT receipt_number, stock_priority
    FROM ranked
    WHERE stock_priority < 10
)
UPDATE ingestion_job job
SET status = 'PENDING',
    attempts = 0,
    priority = selected.stock_priority,
    available_at = CURRENT_TIMESTAMP,
    locked_at = NULL,
    locked_by = NULL,
    last_error_code = 'HTML_V4_REPARSE',
    updated_at = CURRENT_TIMESTAMP
FROM selected
WHERE job.job_type = 'DISCLOSURE_DOCUMENT'
  AND job.business_key = selected.receipt_number;

WITH selected AS (
    SELECT d.id
    FROM disclosure d
    JOIN security s ON s.id = d.security_id
    JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
    WHERE s.active AND s.common_stock
      AND d.receipt_number IN (
          SELECT business_key
          FROM ingestion_job
          WHERE job_type = 'DISCLOSURE_DOCUMENT'
            AND last_error_code = 'HTML_V4_REPARSE'
      )
)
UPDATE disclosure d
SET document_status = 'PENDING', updated_at = CURRENT_TIMESTAMP
FROM selected
WHERE d.id = selected.id;

-- 확장된 75종목 전체를 최근 수집 순서와 무관하게 다시 순환시킨다.
UPDATE news_collection_target target
SET last_collected_at = NULL
FROM security s
JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
WHERE target.security_id = s.id;
