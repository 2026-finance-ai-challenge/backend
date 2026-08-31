CREATE TABLE foreign_limit_prediction_snapshot (
    security_id UUID NOT NULL REFERENCES security (id) ON DELETE CASCADE,
    base_date DATE NOT NULL,
    min_rate NUMERIC(12, 6) NOT NULL,
    base_rate NUMERIC(12, 6) NOT NULL,
    max_rate NUMERIC(12, 6) NOT NULL,
    observation_count INTEGER NOT NULL,
    observation_window_days INTEGER NOT NULL,
    confidence NUMERIC(8, 6) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(128) NOT NULL,
    PRIMARY KEY (security_id, base_date),
    CONSTRAINT foreign_limit_prediction_rate_order CHECK (
        min_rate >= 0 AND min_rate <= base_rate
        AND base_rate <= max_rate AND max_rate <= 100
    ),
    CONSTRAINT foreign_limit_prediction_observations_positive CHECK (
        observation_count > 0 AND observation_window_days > 0
    ),
    CONSTRAINT foreign_limit_prediction_confidence_range CHECK (
        confidence >= 0 AND confidence <= 1
    )
);

CREATE INDEX foreign_limit_prediction_latest_idx
    ON foreign_limit_prediction_snapshot (security_id, base_date DESC);
