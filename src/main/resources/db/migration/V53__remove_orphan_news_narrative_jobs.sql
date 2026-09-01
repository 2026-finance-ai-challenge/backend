DELETE FROM translation_memory memory
WHERE memory.content_kind = 'NEWS_NARRATIVE'
  AND memory.request_context ->> 'article_id' IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM news_article article
      WHERE article.id::text = memory.request_context ->> 'article_id'
  );
