-- 모델 런타임 장애로 소진된 작업만 재개하고 기존 성공 결과는 유지한다.
UPDATE news_article article
SET analysis_status = 'PENDING',
    analyzed_at = NULL
FROM news_analysis_job job
WHERE job.article_id = article.id
  AND job.status = 'FAILED'
  AND job.last_error_code = 'BusinessException';

UPDATE news_analysis_job
SET status = 'PENDING',
    attempts = 0,
    next_attempt_at = CURRENT_TIMESTAMP,
    locked_at = NULL,
    last_error_code = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'FAILED'
  AND last_error_code = 'BusinessException';

UPDATE disclosure disclosure
SET analysis_status = 'PENDING',
    analyzed_at = NULL,
    updated_at = CURRENT_TIMESTAMP
FROM ingestion_job job
WHERE job.job_type = 'DISCLOSURE_SIGNAL'
  AND job.business_key = disclosure.receipt_number
  AND job.status = 'FAILED'
  AND job.last_error_code = 'BusinessException';

UPDATE ingestion_job
SET status = 'PENDING',
    attempts = 0,
    available_at = CURRENT_TIMESTAMP,
    locked_at = NULL,
    locked_by = NULL,
    last_error_code = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE job_type = 'DISCLOSURE_SIGNAL'
  AND status = 'FAILED'
  AND last_error_code = 'BusinessException';
