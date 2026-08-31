-- 한글이 남은 영문 제목은 노출하지 않고 번역 작업으로 되돌린다.
UPDATE news_article
SET english_title = NULL
WHERE english_title ~ '[가-힣ㄱ-ㅎㅏ-ㅣ]';

UPDATE translation_job job
SET status = 'PENDING',
    attempts = 0,
    available_at = CURRENT_TIMESTAMP,
    locked_at = NULL,
    locked_by = NULL,
    last_error_code = NULL,
    updated_at = CURRENT_TIMESTAMP
FROM translation_memory memory
WHERE memory.id = job.translation_memory_id
  AND memory.content_kind IN ('NEWS_TITLE', 'DISCLOSURE_TITLE')
  AND memory.target_locale = 'en'
  AND memory.status = 'READY'
  AND memory.translated_text ~ '[가-힣ㄱ-ㅎㅏ-ㅣ]';

UPDATE translation_memory
SET translated_text = NULL,
    status = 'PENDING',
    model_id = NULL,
    prompt_version = NULL,
    generated_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE content_kind IN ('NEWS_TITLE', 'DISCLOSURE_TITLE')
  AND target_locale = 'en'
  AND status = 'READY'
  AND translated_text ~ '[가-힣ㄱ-ㅎㅏ-ㅣ]';

ALTER TABLE translation_memory
    ADD CONSTRAINT translation_memory_english_title_no_hangul CHECK (
        content_kind NOT IN ('NEWS_TITLE', 'DISCLOSURE_TITLE')
        OR target_locale <> 'en'
        OR status <> 'READY'
        OR (
            translated_text !~ '[가-힣ㄱ-ㅎㅏ-ㅣ]'
            AND translated_text !~* '\y(eok|jo)([ -]?won)?\y|\yman[ -]?won\y'
        )
    );

ALTER TABLE news_article
    ADD CONSTRAINT news_article_english_title_no_hangul CHECK (
        english_title IS NULL OR (
            english_title !~ '[가-힣ㄱ-ㅎㅏ-ㅣ]'
            AND english_title !~* '\y(eok|jo)([ -]?won)?\y|\yman[ -]?won\y'
        )
    );
