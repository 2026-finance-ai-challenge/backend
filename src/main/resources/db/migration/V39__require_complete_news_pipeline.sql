ALTER TABLE news_article
    ALTER COLUMN original_excerpt DROP NOT NULL,
    ADD COLUMN source_policy VARCHAR(100);

UPDATE news_article
SET source_policy = 'legacy_full_article_v1'
WHERE content_availability = 'FULL_ARTICLE'
  AND original_body IS NOT NULL
  AND btrim(original_body) <> '';

ALTER TABLE news_article
    ADD CONSTRAINT news_article_full_content CHECK (
        content_availability <> 'FULL_ARTICLE'
        OR (
            original_body IS NOT NULL
            AND btrim(original_body) <> ''
            AND source_policy IS NOT NULL
            AND btrim(source_policy) <> ''
        )
    );

-- 검색 요약 기반 뉴스와 그 뉴스에만 사용된 번역 캐시를 원문 파이프라인 전환 시 제거한다.
DELETE FROM translation_memory
WHERE content_kind IN ('NEWS_TITLE', 'NEWS_NARRATIVE');

DELETE FROM news_cluster;

UPDATE news_collection_target
SET last_collected_at = NULL;
