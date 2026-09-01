-- 최신 화면에 노출되는 제목을 과거 백필보다 먼저 번역한다.
UPDATE translation_job job
SET priority = 10,
    available_at = LEAST(job.available_at, CURRENT_TIMESTAMP),
    updated_at = CURRENT_TIMESTAMP
FROM translation_memory memory
WHERE memory.id = job.translation_memory_id
  AND memory.content_kind = 'NEWS_TITLE'
  AND memory.created_at >= CURRENT_TIMESTAMP - INTERVAL '7 days'
  AND job.status = 'PENDING';

-- 정적 카탈로그에 없다는 이유로 중단된 공시 제목을 AI 번역으로 재처리한다.
WITH requeued AS (
    UPDATE translation_job job
    SET status = 'PENDING', attempts = 0, priority = 10,
        available_at = CURRENT_TIMESTAMP, locked_at = NULL, locked_by = NULL,
        last_error_code = NULL, updated_at = CURRENT_TIMESTAMP
    FROM translation_memory memory
    WHERE memory.id = job.translation_memory_id
      AND memory.content_kind = 'DISCLOSURE_TITLE'
      AND job.status = 'FAILED'
      AND job.last_error_code = 'CATALOG_ENTRY_MISSING'
    RETURNING memory.id
)
UPDATE translation_memory memory
SET status = 'PENDING', updated_at = CURRENT_TIMESTAMP
FROM requeued
WHERE memory.id = requeued.id;

CREATE INDEX translation_job_recent_title_claim_idx
    ON translation_job (priority, updated_at DESC, translation_memory_id)
    WHERE status = 'PENDING';

-- 영문 완료 데이터에 한글이 남아 있으면 화면에서 숨기고 다시 생성한다.
UPDATE news_article article
SET english_body = NULL,
    what_summary = NULL,
    why_summary = NULL,
    impact_summary = NULL
FROM translation_memory memory
WHERE memory.content_kind = 'NEWS_NARRATIVE'
  AND memory.status = 'READY'
  AND memory.result_payload::text ~ '[가-힣ㄱ-ㅎㅏ-ㅣ]'
  AND article.id = CAST(memory.request_context ->> 'article_id' AS uuid);

WITH requeued AS (
    UPDATE translation_job job
    SET status = 'PENDING', attempts = 0, priority = 0,
        available_at = CURRENT_TIMESTAMP, locked_at = NULL, locked_by = NULL,
        last_error_code = NULL, updated_at = CURRENT_TIMESTAMP
    FROM translation_memory memory
    WHERE memory.id = job.translation_memory_id
      AND memory.content_kind IN ('NEWS_NARRATIVE', 'DISCLOSURE_SECTION')
      AND memory.status = 'READY'
      AND memory.result_payload::text ~ '[가-힣ㄱ-ㅎㅏ-ㅣ]'
    RETURNING memory.id
)
UPDATE translation_memory memory
SET status = 'PENDING', result_payload = NULL, model_id = NULL,
    prompt_version = NULL, generated_at = NULL, updated_at = CURRENT_TIMESTAMP
FROM requeued
WHERE memory.id = requeued.id;

DELETE FROM disclosure_ai_summary
WHERE concat_ws(' ', what_summary, why_summary, impact_summary, refusal_reason)
      ~ '[가-힣ㄱ-ㅎㅏ-ㅣ]';
