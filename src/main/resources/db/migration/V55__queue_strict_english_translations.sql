INSERT INTO translation_memory (
    id, content_kind, source_locale, target_locale, translation_version,
    source_hash, source_text, normalized_source_text, status,
    created_at, updated_at
)
SELECT gen_random_uuid(), 'NEWS_TITLE', 'ko', 'en', 'news-title-v3',
       article.title_source_hash,
       regexp_replace(btrim(min(article.original_title)), '[[:space:]]+', ' ', 'g'),
       regexp_replace(btrim(min(article.original_title)), '[[:space:]]+', ' ', 'g'),
       'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM news_article article
GROUP BY article.title_source_hash
ON CONFLICT (content_kind, source_hash, target_locale, translation_version) DO NOTHING;

INSERT INTO translation_memory (
    id, content_kind, source_locale, target_locale, translation_version,
    source_hash, source_text, normalized_source_text, status,
    created_at, updated_at
)
SELECT gen_random_uuid(), 'DISCLOSURE_TITLE', 'ko', 'en',
       'codex-disclosure-title-v2', disclosure.title_source_hash,
       min(disclosure.title_ko),
       regexp_replace(btrim(min(disclosure.title_ko)), '[[:space:]]+', ' ', 'g'),
       'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM disclosure disclosure
JOIN security security ON security.id = disclosure.security_id
JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
GROUP BY disclosure.title_source_hash
ON CONFLICT (content_kind, source_hash, target_locale, translation_version) DO NOTHING;

INSERT INTO translation_job (
    translation_memory_id, status, attempts, available_at, created_at, updated_at, priority
)
SELECT memory.id, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 10
FROM translation_memory memory
WHERE memory.content_kind IN ('NEWS_TITLE', 'DISCLOSURE_TITLE')
  AND memory.target_locale = 'en'
  AND memory.translation_version IN ('news-title-v3', 'codex-disclosure-title-v2')
  AND memory.status <> 'READY'
ON CONFLICT (translation_memory_id) DO NOTHING;
