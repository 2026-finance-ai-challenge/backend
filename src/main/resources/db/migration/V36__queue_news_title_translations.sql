ALTER TABLE news_article
    ADD COLUMN title_source_hash CHAR(64);

UPDATE news_article
SET title_source_hash = encode(
    digest(regexp_replace(btrim(original_title), '[[:space:]]+', ' ', 'g'), 'sha256'),
    'hex'
);

ALTER TABLE news_article
    ALTER COLUMN title_source_hash SET NOT NULL,
    ADD CONSTRAINT news_article_title_source_hash_format CHECK (
        title_source_hash ~ '^[0-9a-f]{64}$'
    );

CREATE INDEX news_article_title_source_hash_idx ON news_article (title_source_hash);

INSERT INTO translation_memory (
    id, content_kind, source_locale, target_locale, translation_version,
    source_hash, source_text, normalized_source_text, status,
    created_at, updated_at
)
SELECT gen_random_uuid(), 'NEWS_TITLE', 'ko', 'en', 'news-title-v1',
       title_source_hash,
       regexp_replace(btrim(min(original_title)), '[[:space:]]+', ' ', 'g'),
       regexp_replace(btrim(min(original_title)), '[[:space:]]+', ' ', 'g'),
       'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM news_article
GROUP BY title_source_hash
ON CONFLICT (content_kind, source_hash, target_locale, translation_version) DO NOTHING;

INSERT INTO translation_job (
    translation_memory_id, status, attempts, available_at, created_at, updated_at
)
SELECT memory.id, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM translation_memory memory
WHERE memory.content_kind = 'NEWS_TITLE'
  AND memory.target_locale = 'en'
  AND memory.translation_version = 'news-title-v1'
  AND memory.status <> 'READY'
ON CONFLICT (translation_memory_id) DO NOTHING;
