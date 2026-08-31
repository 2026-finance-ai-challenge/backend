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

-- 운영 전환 이전 데이터를 같은 트랜잭션에서 복구 가능한 별도 스키마로 보존한다.
CREATE SCHEMA IF NOT EXISTS kmarket_archive;

CREATE TABLE kmarket_archive.news_cluster_legacy_20260831 AS
SELECT * FROM news_cluster;

CREATE TABLE kmarket_archive.news_article_legacy_20260831 AS
SELECT * FROM news_article;

CREATE TABLE kmarket_archive.news_article_security_legacy_20260831 AS
SELECT * FROM news_article_security;

CREATE TABLE kmarket_archive.news_analysis_job_legacy_20260831 AS
SELECT * FROM news_analysis_job;

CREATE TABLE kmarket_archive.financial_term_click_legacy_20260831 AS
SELECT * FROM financial_term_explanation_click;

CREATE TABLE kmarket_archive.news_translation_memory_legacy_20260831 AS
SELECT * FROM translation_memory
WHERE content_kind IN ('NEWS_TITLE', 'NEWS_NARRATIVE');

CREATE TABLE kmarket_archive.news_translation_job_legacy_20260831 AS
SELECT job.*
FROM translation_job job
JOIN translation_memory memory ON memory.id = job.translation_memory_id
WHERE memory.content_kind IN ('NEWS_TITLE', 'NEWS_NARRATIVE');

-- 검색 요약 기반 뉴스와 그 뉴스에만 사용된 번역 캐시를 원문 파이프라인 전환 시 제거한다.
DELETE FROM translation_memory
WHERE content_kind IN ('NEWS_TITLE', 'NEWS_NARRATIVE');

DELETE FROM news_cluster;

UPDATE news_collection_target
SET last_collected_at = NULL;
