CREATE TABLE market_foreign_net_flow (
    market_code VARCHAR(8) NOT NULL,
    trading_date DATE NOT NULL,
    net_purchase_amount_krw NUMERIC(24, 2) NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(100) NOT NULL,
    PRIMARY KEY (market_code, trading_date),
    CONSTRAINT market_foreign_net_flow_market CHECK (
        market_code IN ('KOSPI', 'KOSDAQ')
    )
);

CREATE INDEX market_foreign_net_flow_latest_idx
    ON market_foreign_net_flow (trading_date DESC, market_code);
