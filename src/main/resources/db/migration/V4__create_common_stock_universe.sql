ALTER TABLE security
    ADD COLUMN common_stock BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN master_updated_at TIMESTAMPTZ;

CREATE INDEX security_common_stock_idx
    ON security (active, common_stock, market, stock_code);

ALTER TABLE disclosure
    DROP CONSTRAINT disclosure_type_value;

ALTER TABLE disclosure
    ADD CONSTRAINT disclosure_type_value CHECK (disclosure_type BETWEEN 'A' AND 'J');
