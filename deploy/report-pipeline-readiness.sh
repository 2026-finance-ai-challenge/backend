#!/usr/bin/env bash
set -euo pipefail

cd /opt/kmarket

# 콘텐츠나 비밀값 없이 파이프라인 단계별 건수만 배포 로그에 남긴다.
docker compose --env-file runtime.env -f compose.prod.yaml exec -T postgres \
  psql -U kmarket -d kmarket -At -v ON_ERROR_STOP=1 <<'SQL'
SELECT 'news.collected_1h=' || count(*)
FROM news_article
WHERE published_at >= CURRENT_TIMESTAMP - INTERVAL '1 hour';

SELECT 'news.full_article_1h=' || count(*)
FROM news_article
WHERE published_at >= CURRENT_TIMESTAMP - INTERVAL '1 hour'
  AND content_availability = 'FULL_ARTICLE';

SELECT 'news.analysis.' || status || '=' || count(*)
FROM news_analysis_job
GROUP BY status
ORDER BY status;

SELECT 'news.analysis_error.' || COALESCE(last_error_code, 'NONE') || '=' || count(*)
FROM news_analysis_job
WHERE status = 'FAILED'
GROUP BY last_error_code
ORDER BY count(*) DESC;

SELECT 'disclosure.document_ready=' || count(*)
FROM disclosure
WHERE document_status = 'READY';

SELECT 'disclosure.index_ready=' || count(*)
FROM disclosure
WHERE index_status = 'READY';

SELECT 'disclosure.analysis_ready=' || count(*)
FROM disclosure
WHERE analysis_status = 'READY';

SELECT 'disclosure.title_ready=' || count(*)
FROM disclosure disclosure
JOIN translation_memory translation
  ON translation.content_kind = 'DISCLOSURE_TITLE'
 AND translation.source_hash = disclosure.title_source_hash
 AND translation.target_locale = 'en'
 AND translation.translation_version = 'codex-disclosure-title-v1'
 AND translation.status = 'READY';

SELECT 'disclosure.signal.' || status || '=' || count(*)
FROM ingestion_job
WHERE job_type = 'DISCLOSURE_SIGNAL'
GROUP BY status
ORDER BY status;

SELECT 'disclosure.signal_error.' || COALESCE(last_error_code, 'NONE') || '=' || count(*)
FROM ingestion_job
WHERE job_type = 'DISCLOSURE_SIGNAL'
  AND status = 'FAILED'
GROUP BY last_error_code
ORDER BY count(*) DESC;
SQL
