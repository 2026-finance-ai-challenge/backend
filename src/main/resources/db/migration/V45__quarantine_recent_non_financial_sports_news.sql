-- 승인된 최근 1시간 범위에서 원문은 보존하고 비금융 스포츠 기사만 격리한다.
CREATE TEMPORARY TABLE irrelevant_sports_news_id ON COMMIT DROP AS
SELECT id
FROM news_article
WHERE collected_at >= CURRENT_TIMESTAMP - INTERVAL '1 hour'
  AND lower(original_title) ~
      '(야구|축구|농구|배구|양궁|배드민턴|선수|감독|투수|타자|홈런|지명할당|fa 선택|다승왕|올림픽|kbo|mlb|soccer|football|baseball|basketball|badminton|player|coach|pitcher)'
  AND lower(original_title) !~
      '(주가|증권|주식|상장|코스피|코스닥|공시|배당|실적|매출|영업이익|순이익|시가총액|목표가|stock|share|listed|kospi|kosdaq|dividend|earnings|revenue|profit)';

UPDATE translation_job job
SET status = 'FAILED',
    locked_at = NULL,
    locked_by = NULL,
    last_error_code = 'IRRELEVANT_SPORTS_CONTEXT',
    updated_at = CURRENT_TIMESTAMP
FROM translation_memory memory, irrelevant_sports_news_id irrelevant
WHERE job.translation_memory_id = memory.id
  AND memory.content_kind = 'NEWS_NARRATIVE'
  AND memory.request_context ->> 'article_id' = irrelevant.id::text;

UPDATE translation_memory memory
SET status = 'FAILED',
    updated_at = CURRENT_TIMESTAMP
FROM irrelevant_sports_news_id irrelevant
WHERE memory.content_kind = 'NEWS_NARRATIVE'
  AND memory.request_context ->> 'article_id' = irrelevant.id::text;

UPDATE news_article article
SET analysis_status = 'FAILED'
FROM irrelevant_sports_news_id irrelevant
WHERE article.id = irrelevant.id;
