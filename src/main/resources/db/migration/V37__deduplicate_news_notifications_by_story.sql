CREATE OR REPLACE FUNCTION notify_watchlist_news() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO user_notification (
        id, user_id, notification_type, title, body,
        reference_type, reference_id, created_at
    )
    SELECT gen_random_uuid(), watchlist.user_id, 'NEWS',
           LEFT(article.original_title, 200),
           LEFT(COALESCE(NULLIF(article.original_excerpt, ''), 'New watchlist news is available.'), 1000),
           'NEWS', COALESCE(cluster.representative_article_id, article.id)::text,
           article.collected_at
    FROM watchlist_item watchlist
    JOIN news_article article ON article.id = NEW.article_id
    JOIN news_cluster cluster ON cluster.id = article.cluster_id
    WHERE watchlist.security_id = NEW.security_id
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;
