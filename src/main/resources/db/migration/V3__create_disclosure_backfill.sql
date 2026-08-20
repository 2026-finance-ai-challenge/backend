ALTER TABLE security
    DROP CONSTRAINT security_stock_code_format;

ALTER TABLE security
    ADD CONSTRAINT security_stock_code_format
        CHECK (stock_code ~ '^[0-9A-Z]{6}$');

ALTER TABLE disclosure
    DROP CONSTRAINT disclosure_document_status_value;

ALTER TABLE disclosure
    ADD CONSTRAINT disclosure_document_status_value
        CHECK (document_status IN ('PENDING', 'READY', 'UNAVAILABLE', 'FAILED'));

ALTER TABLE disclosure
    DROP CONSTRAINT disclosure_index_status_value;

ALTER TABLE disclosure
    ADD CONSTRAINT disclosure_index_status_value
        CHECK (index_status IN ('PENDING', 'READY', 'UNAVAILABLE', 'FAILED'));

CREATE TABLE disclosure_backfill_job (
    id UUID PRIMARY KEY,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    next_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    run_id UUID,
    collected_count BIGINT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(100),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT disclosure_backfill_range_valid CHECK (from_date <= to_date),
    CONSTRAINT disclosure_backfill_next_date_valid CHECK (next_date <= to_date + 1),
    CONSTRAINT disclosure_backfill_status_value CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT disclosure_backfill_count_nonnegative CHECK (collected_count >= 0),
    CONSTRAINT disclosure_backfill_range_unique UNIQUE (from_date, to_date)
);

CREATE INDEX disclosure_backfill_status_idx
    ON disclosure_backfill_job (status, updated_at);
