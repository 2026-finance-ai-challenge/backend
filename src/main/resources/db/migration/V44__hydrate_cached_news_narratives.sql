-- 최근 뉴스의 기존 번역 캐시를 공개 조회 컬럼에 반영한다.
UPDATE news_article article
SET english_body = narrative.english_body,
    what_summary = narrative.what_summary,
    why_summary = narrative.why_summary,
    impact_summary = narrative.impact_summary
FROM (
    SELECT CAST(memory.request_context ->> 'article_id' AS uuid) AS article_id,
           (
               SELECT string_agg(paragraph.value, E'\n\n' ORDER BY paragraph.ordinality)
               FROM jsonb_array_elements_text(
                   memory.result_payload -> 'translatedParagraphs'
               ) WITH ORDINALITY AS paragraph(value, ordinality)
           ) AS english_body,
           memory.result_payload ->> 'what' AS what_summary,
           memory.result_payload ->> 'why' AS why_summary,
           memory.result_payload ->> 'impact' AS impact_summary
    FROM translation_memory memory
    WHERE memory.content_kind = 'NEWS_NARRATIVE'
      AND memory.target_locale = 'en'
      AND memory.status = 'READY'
      AND memory.request_context ->> 'article_id' IS NOT NULL
) narrative
WHERE article.id = narrative.article_id
  AND article.published_at >= CURRENT_TIMESTAMP - INTERVAL '1 hour'
  AND narrative.english_body IS NOT NULL
  AND btrim(narrative.english_body) <> ''
  AND narrative.english_body !~ '[가-힣ㄱ-ㅎㅏ-ㅣ]'
  AND narrative.english_body !~* '\y(eok|jo)([ -]?won)?\y|\yman[ -]?won\y'
  AND narrative.what_summary IS NOT NULL
  AND btrim(narrative.what_summary) <> ''
  AND narrative.what_summary !~ '[가-힣ㄱ-ㅎㅏ-ㅣ]'
  AND narrative.what_summary !~* '\y(eok|jo)([ -]?won)?\y|\yman[ -]?won\y'
  AND narrative.why_summary IS NOT NULL
  AND btrim(narrative.why_summary) <> ''
  AND narrative.why_summary !~ '[가-힣ㄱ-ㅎㅏ-ㅣ]'
  AND narrative.why_summary !~* '\y(eok|jo)([ -]?won)?\y|\yman[ -]?won\y'
  AND narrative.impact_summary IS NOT NULL
  AND btrim(narrative.impact_summary) <> ''
  AND narrative.impact_summary !~ '[가-힣ㄱ-ㅎㅏ-ㅣ]'
  AND narrative.impact_summary !~* '\y(eok|jo)([ -]?won)?\y|\yman[ -]?won\y';
