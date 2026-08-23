CREATE UNIQUE INDEX user_notification_source_once_idx
    ON user_notification (user_id, notification_type, reference_type, reference_id)
    WHERE notification_type IN ('NEWS', 'DISCLOSURE') AND reference_id IS NOT NULL;

CREATE FUNCTION notify_watchlist_news() RETURNS TRIGGER
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
           'NEWS', article.id::text, article.collected_at
    FROM watchlist_item watchlist
    JOIN news_article article ON article.id = NEW.article_id
    WHERE watchlist.security_id = NEW.security_id
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER news_watchlist_notification_trigger
AFTER INSERT ON news_article_security
FOR EACH ROW EXECUTE FUNCTION notify_watchlist_news();

CREATE FUNCTION notify_watchlist_disclosure() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.security_id IS NULL THEN
        RETURN NEW;
    END IF;
    INSERT INTO user_notification (
        id, user_id, notification_type, title, body,
        reference_type, reference_id, created_at
    )
    SELECT gen_random_uuid(), watchlist.user_id, 'DISCLOSURE',
           LEFT(NEW.title_ko, 200),
           LEFT('A new OpenDART filing was submitted for a watchlist company.', 1000),
           'FILING', NEW.receipt_number, NEW.detected_at
    FROM watchlist_item watchlist
    WHERE watchlist.security_id = NEW.security_id
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER disclosure_watchlist_notification_trigger
AFTER INSERT ON disclosure
FOR EACH ROW EXECUTE FUNCTION notify_watchlist_disclosure();

CREATE FUNCTION notify_watchlist_trading_caution() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    was_caution BOOLEAN;
    is_caution BOOLEAN;
BEGIN
    is_caution := COALESCE(NEW.vi_active, FALSE)
        OR COALESCE(NEW.single_price_trading, FALSE)
        OR COALESCE(NEW.trading_halted, FALSE)
        OR COALESCE(NEW.price_limit_state, 'NONE') <> 'NONE'
        OR NEW.data_status IN ('DELAYED', 'STALE');
    was_caution := CASE WHEN TG_OP = 'UPDATE' THEN
        COALESCE(OLD.vi_active, FALSE)
        OR COALESCE(OLD.single_price_trading, FALSE)
        OR COALESCE(OLD.trading_halted, FALSE)
        OR COALESCE(OLD.price_limit_state, 'NONE') <> 'NONE'
        OR OLD.data_status IN ('DELAYED', 'STALE')
    ELSE FALSE END;
    IF NOT is_caution OR was_caution THEN
        RETURN NEW;
    END IF;
    INSERT INTO user_notification (
        id, user_id, notification_type, title, body,
        reference_type, reference_id, created_at
    )
    SELECT gen_random_uuid(), watchlist.user_id, 'TRADING_CAUTION',
           LEFT(issuer.name_ko || ' trading caution', 200),
           LEFT(CONCAT_WS(' · ',
               CASE WHEN NEW.trading_halted THEN COALESCE(NEW.trading_halt_reason, 'Trading halted') END,
               CASE WHEN NEW.vi_active THEN 'Volatility interruption active' END,
               CASE WHEN NEW.single_price_trading THEN 'Single-price trading' END,
               CASE WHEN COALESCE(NEW.price_limit_state, 'NONE') <> 'NONE' THEN 'Price limit ' || NEW.price_limit_state END,
               CASE WHEN NEW.data_status IN ('DELAYED', 'STALE') THEN 'Market data ' || NEW.data_status END
           ), 1000),
           'STOCK', security.stock_code, NEW.received_at
    FROM watchlist_item watchlist
    JOIN security ON security.id = watchlist.security_id
    JOIN issuer ON issuer.id = security.issuer_id
    WHERE watchlist.security_id = NEW.security_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trading_caution_watchlist_notification_trigger
AFTER INSERT OR UPDATE ON market_quote_snapshot
FOR EACH ROW EXECUTE FUNCTION notify_watchlist_trading_caution();
