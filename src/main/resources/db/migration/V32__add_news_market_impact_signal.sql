ALTER TABLE news_article
    ADD COLUMN market_impact_importance VARCHAR(16),
    ADD COLUMN market_impact_score NUMERIC(7,6),
    ADD CONSTRAINT news_article_market_impact_importance CHECK (
        market_impact_importance IS NULL
        OR market_impact_importance IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    ADD CONSTRAINT news_article_market_impact_score CHECK (
        market_impact_score IS NULL OR market_impact_score BETWEEN 0 AND 1
    );

CREATE INDEX news_article_market_impact_signal_idx
    ON news_article (market_impact_importance, market_impact_score DESC, published_at DESC);
