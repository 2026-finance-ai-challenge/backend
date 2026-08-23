CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE security
    ADD COLUMN sector VARCHAR(120),
    ADD COLUMN isin_code CHAR(12);

CREATE UNIQUE INDEX security_isin_code_idx
    ON security (isin_code)
    WHERE isin_code IS NOT NULL;

CREATE TABLE stock_alias (
    security_id UUID NOT NULL REFERENCES security (id) ON DELETE CASCADE,
    alias VARCHAR(120) NOT NULL,
    normalized_alias VARCHAR(120) NOT NULL,
    locale VARCHAR(8) NOT NULL DEFAULT 'en',
    PRIMARY KEY (security_id, normalized_alias),
    CONSTRAINT stock_alias_normalized_nonempty CHECK (normalized_alias <> '')
);

CREATE INDEX stock_alias_search_idx ON stock_alias USING GIN (normalized_alias gin_trgm_ops);
CREATE INDEX issuer_name_ko_search_idx ON issuer USING GIN (LOWER(name_ko) gin_trgm_ops);
CREATE INDEX issuer_name_en_search_idx ON issuer USING GIN (LOWER(COALESCE(name_en, '')) gin_trgm_ops);

CREATE TABLE market_quote_snapshot (
    security_id UUID PRIMARY KEY REFERENCES security (id) ON DELETE CASCADE,
    current_price_krw NUMERIC(20, 4) NOT NULL,
    change_amount_krw NUMERIC(20, 4) NOT NULL,
    change_rate NUMERIC(12, 6) NOT NULL,
    open_price_krw NUMERIC(20, 4),
    high_price_krw NUMERIC(20, 4),
    low_price_krw NUMERIC(20, 4),
    volume BIGINT NOT NULL,
    market_session VARCHAR(24) NOT NULL,
    vi_active BOOLEAN,
    single_price_trading BOOLEAN,
    price_limit_state VARCHAR(16),
    trading_halted BOOLEAN,
    trading_halt_reason VARCHAR(300),
    status_available BOOLEAN NOT NULL DEFAULT FALSE,
    data_status VARCHAR(16) NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(64) NOT NULL,
    CONSTRAINT market_quote_prices_nonnegative CHECK (
        current_price_krw >= 0
        AND (open_price_krw IS NULL OR open_price_krw >= 0)
        AND (high_price_krw IS NULL OR high_price_krw >= 0)
        AND (low_price_krw IS NULL OR low_price_krw >= 0)
    ),
    CONSTRAINT market_quote_volume_nonnegative CHECK (volume >= 0),
    CONSTRAINT market_quote_price_limit_state CHECK (
        price_limit_state IS NULL OR price_limit_state IN ('NONE', 'UPPER', 'LOWER')
    ),
    CONSTRAINT market_quote_data_status CHECK (
        data_status IN ('LIVE', 'DELAYED', 'CLOSED', 'STALE')
    )
);

CREATE INDEX market_quote_sort_idx
    ON market_quote_snapshot (change_rate DESC, volume DESC, security_id);

CREATE TABLE market_index_snapshot (
    index_code VARCHAR(4) PRIMARY KEY,
    index_name VARCHAR(30) NOT NULL,
    current_value NUMERIC(20, 6) NOT NULL,
    change_amount NUMERIC(20, 6) NOT NULL,
    change_rate NUMERIC(12, 6) NOT NULL,
    volume BIGINT,
    data_status VARCHAR(16) NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(64) NOT NULL,
    CONSTRAINT market_index_code CHECK (index_code IN ('0001', '1001', '2001')),
    CONSTRAINT market_index_data_status CHECK (
        data_status IN ('LIVE', 'DELAYED', 'CLOSED', 'STALE')
    )
);

CREATE TABLE exchange_rate_snapshot (
    currency CHAR(3) PRIMARY KEY,
    krw_per_unit NUMERIC(20, 8) NOT NULL,
    data_status VARCHAR(16) NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    source VARCHAR(64) NOT NULL,
    CONSTRAINT exchange_rate_positive CHECK (krw_per_unit > 0),
    CONSTRAINT exchange_rate_data_status CHECK (
        data_status IN ('LIVE', 'DELAYED', 'CLOSED', 'STALE')
    )
);

CREATE TABLE market_daily_price (
    security_id UUID NOT NULL REFERENCES security (id) ON DELETE CASCADE,
    trading_date DATE NOT NULL,
    open_price_krw NUMERIC(20, 4) NOT NULL,
    high_price_krw NUMERIC(20, 4) NOT NULL,
    low_price_krw NUMERIC(20, 4) NOT NULL,
    close_price_krw NUMERIC(20, 4) NOT NULL,
    volume BIGINT NOT NULL,
    source VARCHAR(64) NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (security_id, trading_date),
    CONSTRAINT market_daily_prices_nonnegative CHECK (
        open_price_krw >= 0 AND high_price_krw >= 0
        AND low_price_krw >= 0 AND close_price_krw >= 0
    ),
    CONSTRAINT market_daily_price_range CHECK (
        high_price_krw >= GREATEST(open_price_krw, close_price_krw, low_price_krw)
        AND low_price_krw <= LEAST(open_price_krw, close_price_krw, high_price_krw)
    ),
    CONSTRAINT market_daily_volume_nonnegative CHECK (volume >= 0)
);

CREATE INDEX market_daily_price_lookup_idx
    ON market_daily_price (security_id, trading_date DESC);

CREATE TABLE foreign_limit_policy (
    stock_code VARCHAR(6) PRIMARY KEY REFERENCES service_stock_universe (stock_code),
    warning_threshold NUMERIC(8, 4) NOT NULL DEFAULT 90.0000,
    effective_from DATE NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT foreign_limit_warning_range CHECK (
        warning_threshold > 0 AND warning_threshold <= 100
    )
);

INSERT INTO foreign_limit_policy (stock_code, warning_threshold, effective_from)
VALUES
    ('003490', 90.0000, DATE '2026-08-23'),
    ('015760', 90.0000, DATE '2026-08-23'),
    ('017670', 90.0000, DATE '2026-08-23'),
    ('032640', 90.0000, DATE '2026-08-23');

CREATE TABLE foreign_ownership_snapshot (
    security_id UUID NOT NULL REFERENCES security (id) ON DELETE CASCADE,
    base_date DATE NOT NULL,
    foreign_owned_quantity BIGINT NOT NULL,
    total_listed_quantity BIGINT,
    foreign_limit_quantity BIGINT,
    available_quantity BIGINT,
    ownership_rate NUMERIC(12, 6),
    limit_exhaustion_rate NUMERIC(12, 6),
    collected_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(64) NOT NULL,
    PRIMARY KEY (security_id, base_date),
    CONSTRAINT foreign_ownership_quantities_nonnegative CHECK (
        foreign_owned_quantity >= 0
        AND (total_listed_quantity IS NULL OR total_listed_quantity >= 0)
        AND (foreign_limit_quantity IS NULL OR foreign_limit_quantity >= 0)
        AND (available_quantity IS NULL OR available_quantity >= 0)
    ),
    CONSTRAINT foreign_ownership_rates_nonnegative CHECK (
        (ownership_rate IS NULL OR ownership_rate >= 0)
        AND (limit_exhaustion_rate IS NULL OR limit_exhaustion_rate >= 0)
    )
);

CREATE INDEX foreign_ownership_latest_idx
    ON foreign_ownership_snapshot (security_id, base_date DESC);
