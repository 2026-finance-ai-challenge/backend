ALTER TABLE translation_memory
    DROP CONSTRAINT translation_memory_english_title_no_hangul;

ALTER TABLE news_article
    DROP CONSTRAINT news_article_english_title_no_hangul;

UPDATE news_article
SET english_title = NULL
WHERE english_title ~ '[ㄱ-ㅎㅏ-ㅣ가-힣ぁ-ヿ一-鿿]'
   OR english_title ~* '\y(eok|jo)([ -]?won)?\y|\yman[ -]?won\y';

UPDATE translation_job job
SET status = 'FAILED',
    locked_at = NULL,
    locked_by = NULL,
    last_error_code = 'NON_ENGLISH_TITLE',
    updated_at = CURRENT_TIMESTAMP
FROM translation_memory memory
WHERE memory.id = job.translation_memory_id
  AND memory.content_kind IN ('NEWS_TITLE', 'DISCLOSURE_TITLE')
  AND memory.target_locale = 'en'
  AND memory.status = 'READY'
  AND (
      memory.translated_text ~ '[ㄱ-ㅎㅏ-ㅣ가-힣ぁ-ヿ一-鿿]'
      OR memory.translated_text ~* '\y(eok|jo)([ -]?won)?\y|\yman[ -]?won\y'
  );

UPDATE translation_memory
SET translated_text = NULL,
    status = 'FAILED',
    model_id = NULL,
    prompt_version = NULL,
    generated_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE content_kind IN ('NEWS_TITLE', 'DISCLOSURE_TITLE')
  AND target_locale = 'en'
  AND status = 'READY'
  AND (
      translated_text ~ '[ㄱ-ㅎㅏ-ㅣ가-힣ぁ-ヿ一-鿿]'
      OR translated_text ~* '\y(eok|jo)([ -]?won)?\y|\yman[ -]?won\y'
  );

ALTER TABLE translation_memory
    ADD CONSTRAINT translation_memory_english_title_script CHECK (
        content_kind NOT IN ('NEWS_TITLE', 'DISCLOSURE_TITLE')
        OR target_locale <> 'en'
        OR status <> 'READY'
        OR (
            translated_text !~ '[ㄱ-ㅎㅏ-ㅣ가-힣ぁ-ヿ一-鿿]'
            AND translated_text !~* '\y(eok|jo)([ -]?won)?\y|\yman[ -]?won\y'
        )
    );

ALTER TABLE news_article
    ADD CONSTRAINT news_article_english_title_script CHECK (
        english_title IS NULL OR (
            english_title !~ '[ㄱ-ㅎㅏ-ㅣ가-힣ぁ-ヿ一-鿿]'
            AND english_title !~* '\y(eok|jo)([ -]?won)?\y|\yman[ -]?won\y'
        )
    );

INSERT INTO translation_memory (
    id, content_kind, source_locale, target_locale, translation_version,
    source_hash, source_text, normalized_source_text, status,
    created_at, updated_at
)
SELECT gen_random_uuid(), 'NEWS_TITLE', 'ko', 'en', 'news-title-v2',
       article.title_source_hash,
       regexp_replace(btrim(min(article.original_title)), '[[:space:]]+', ' ', 'g'),
       regexp_replace(btrim(min(article.original_title)), '[[:space:]]+', ' ', 'g'),
       'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM news_article article
WHERE article.english_title IS NULL OR btrim(article.english_title) = ''
GROUP BY article.title_source_hash
ON CONFLICT (content_kind, source_hash, target_locale, translation_version) DO NOTHING;

INSERT INTO translation_job (
    translation_memory_id, status, attempts, available_at, created_at, updated_at, priority
)
SELECT memory.id, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 10
FROM translation_memory memory
WHERE memory.content_kind = 'NEWS_TITLE'
  AND memory.target_locale = 'en'
  AND memory.translation_version = 'news-title-v2'
  AND memory.status <> 'READY'
ON CONFLICT (translation_memory_id) DO NOTHING;
