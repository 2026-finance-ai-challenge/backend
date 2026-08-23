CREATE TABLE news_cluster (
    id UUID PRIMARY KEY,
    signature_hash CHAR(64) NOT NULL UNIQUE,
    normalized_title VARCHAR(1000) NOT NULL,
    representative_article_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE news_article (
    id UUID PRIMARY KEY,
    cluster_id UUID NOT NULL REFERENCES news_cluster (id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    provider_article_id VARCHAR(128) NOT NULL,
    original_title VARCHAR(1000) NOT NULL,
    original_excerpt TEXT NOT NULL,
    original_body TEXT,
    english_title VARCHAR(1000),
    english_body TEXT,
    what_summary VARCHAR(2000),
    why_summary VARCHAR(2000),
    impact_summary VARCHAR(2000),
    event_type VARCHAR(100),
    sentiment VARCHAR(16),
    importance VARCHAR(16),
    market_impact VARCHAR(16),
    event_confidence NUMERIC(5,4),
    sentiment_confidence NUMERIC(5,4),
    importance_confidence NUMERIC(5,4),
    market_impact_confidence NUMERIC(5,4),
    original_url VARCHAR(2000) NOT NULL,
    canonical_url VARCHAR(2000) NOT NULL,
    canonical_url_hash CHAR(64) NOT NULL UNIQUE,
    publisher VARCHAR(200),
    thumbnail_url VARCHAR(2000),
    content_availability VARCHAR(24) NOT NULL,
    analysis_status VARCHAR(16) NOT NULL,
    model_id VARCHAR(100),
    prompt_version VARCHAR(100),
    duplicate_score NUMERIC(5,4),
    published_at TIMESTAMPTZ NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    analyzed_at TIMESTAMPTZ,
    CONSTRAINT news_article_content_availability CHECK (
        content_availability IN ('FULL_ARTICLE', 'SOURCE_EXCERPT', 'UNAVAILABLE')
    ),
    CONSTRAINT news_article_analysis_status CHECK (
        analysis_status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')
    ),
    CONSTRAINT news_article_sentiment CHECK (
        sentiment IS NULL OR sentiment IN ('POSITIVE', 'NEUTRAL', 'NEGATIVE', 'MIXED')
    ),
    CONSTRAINT news_article_importance CHECK (
        importance IS NULL OR importance IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    CONSTRAINT news_article_market_impact CHECK (
        market_impact IS NULL OR market_impact IN ('POSITIVE', 'NEUTRAL', 'NEGATIVE', 'UNCERTAIN')
    )
);

ALTER TABLE news_cluster
    ADD CONSTRAINT news_cluster_representative_fk
    FOREIGN KEY (representative_article_id) REFERENCES news_article (id) ON DELETE SET NULL;

CREATE INDEX news_article_feed_idx
    ON news_article (published_at DESC, id DESC);
CREATE INDEX news_article_filter_idx
    ON news_article (sentiment, importance, market_impact, published_at DESC);
CREATE INDEX news_article_cluster_idx
    ON news_article (cluster_id, published_at DESC);

CREATE TABLE news_article_security (
    article_id UUID NOT NULL REFERENCES news_article (id) ON DELETE CASCADE,
    security_id UUID NOT NULL REFERENCES security (id) ON DELETE CASCADE,
    match_confidence NUMERIC(5,4) NOT NULL,
    PRIMARY KEY (article_id, security_id)
);

CREATE INDEX news_article_security_feed_idx
    ON news_article_security (security_id, article_id);

CREATE TABLE news_analysis_job (
    article_id UUID PRIMARY KEY REFERENCES news_article (id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ,
    last_error_code VARCHAR(100),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT news_analysis_job_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')
    ),
    CONSTRAINT news_analysis_job_attempts CHECK (attempts BETWEEN 0 AND 10)
);

CREATE INDEX news_analysis_job_claim_idx
    ON news_analysis_job (next_attempt_at, updated_at, article_id)
    WHERE status = 'PENDING';

CREATE TABLE news_collection_target (
    security_id UUID PRIMARY KEY REFERENCES security (id) ON DELETE CASCADE,
    last_collected_at TIMESTAMPTZ
);

INSERT INTO news_collection_target (security_id)
SELECT s.id
FROM security s
JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
ON CONFLICT DO NOTHING;

CREATE TABLE financial_term_reference (
    id UUID PRIMARY KEY,
    term_ko VARCHAR(200) NOT NULL,
    normalized_term VARCHAR(200) NOT NULL,
    title_en VARCHAR(500) NOT NULL,
    definition_en VARCHAR(3000) NOT NULL,
    source_name VARCHAR(200) NOT NULL,
    source_url VARCHAR(2000),
    reviewed_at DATE NOT NULL,
    UNIQUE (normalized_term, source_name)
);

CREATE INDEX financial_term_reference_search_idx
    ON financial_term_reference USING GIN (normalized_term gin_trgm_ops);

INSERT INTO financial_term_reference (
    id, term_ko, normalized_term, title_en, definition_en,
    source_name, source_url, reviewed_at
) VALUES
    ('30000000-0000-0000-0000-000000000001', '유상증자', '유상증자', 'Rights offering',
     'An equity financing transaction in which a company issues new shares for payment.',
     'KRX financial glossary', 'https://global.krx.co.kr', '2026-08-23'),
    ('30000000-0000-0000-0000-000000000002', '전환사채', '전환사채', 'Convertible bond',
     'A bond that may be converted into shares under predefined terms.',
     'KRX financial glossary', 'https://global.krx.co.kr', '2026-08-23'),
    ('30000000-0000-0000-0000-000000000003', '공매도', '공매도', 'Short selling',
     'A transaction that sells borrowed securities with an obligation to return equivalent securities.',
     'KRX financial glossary', 'https://global.krx.co.kr', '2026-08-23'),
    ('30000000-0000-0000-0000-000000000004', '거래정지', '거래정지', 'Trading suspension',
     'A temporary halt in trading imposed under applicable market rules.',
     'KRX financial glossary', 'https://global.krx.co.kr', '2026-08-23'),
    ('30000000-0000-0000-0000-000000000005', '자사주', '자사주', 'Treasury shares',
     'Shares issued by a company and subsequently held by that company.',
     'KRX financial glossary', 'https://global.krx.co.kr', '2026-08-23');

CREATE TABLE financial_term_explanation_click (
    id UUID PRIMARY KEY,
    article_id UUID NOT NULL REFERENCES news_article (id) ON DELETE CASCADE,
    user_id UUID REFERENCES user_account (id) ON DELETE SET NULL,
    selected_text_hash CHAR(64) NOT NULL,
    client_ip_hash CHAR(64) NOT NULL,
    clicked_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX financial_term_click_article_time_idx
    ON financial_term_explanation_click (article_id, clicked_at DESC);
