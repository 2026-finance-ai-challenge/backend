CREATE TABLE ingestion_provider_throttle (
    provider VARCHAR(50) PRIMARY KEY,
    blocked_until TIMESTAMPTZ NOT NULL,
    reason VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
