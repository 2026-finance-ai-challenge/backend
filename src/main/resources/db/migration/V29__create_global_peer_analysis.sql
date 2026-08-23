CREATE TABLE global_peer_analysis (
    stock_code VARCHAR(6) PRIMARY KEY REFERENCES service_stock_universe (stock_code),
    data_version VARCHAR(100) NOT NULL,
    analysis JSONB NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT global_peer_data_version_nonempty CHECK (data_version <> '')
);

CREATE INDEX global_peer_analysis_version_idx
    ON global_peer_analysis (data_version, generated_at DESC);
