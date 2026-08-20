CREATE TABLE disclosure_archive (
    id UUID PRIMARY KEY,
    disclosure_id UUID NOT NULL REFERENCES disclosure (id) ON DELETE CASCADE,
    receipt_number VARCHAR(14) NOT NULL,
    stock_code VARCHAR(6) NOT NULL,
    stock_name_ko VARCHAR(200) NOT NULL,
    archive_kind VARCHAR(32) NOT NULL,
    archive_status VARCHAR(16) NOT NULL,
    relative_path VARCHAR(1000) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT disclosure_archive_kind_value CHECK (
        archive_kind IN ('OPENDART_ZIP', 'DART_VIEWER_HTML')
    ),
    CONSTRAINT disclosure_archive_status_value CHECK (
        archive_status IN ('VERIFIED', 'REJECTED')
    ),
    CONSTRAINT disclosure_archive_sha256_format CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT disclosure_archive_size_positive CHECK (size_bytes > 0),
    CONSTRAINT disclosure_archive_receipt_format CHECK (receipt_number ~ '^[0-9]{14}$'),
    CONSTRAINT disclosure_archive_stock_code_format CHECK (stock_code ~ '^[0-9A-Z]{6}$'),
    CONSTRAINT disclosure_archive_current_kind UNIQUE (disclosure_id, archive_kind)
);

CREATE INDEX disclosure_archive_stock_idx
    ON disclosure_archive (stock_code, receipt_number);

CREATE INDEX disclosure_archive_status_idx
    ON disclosure_archive (archive_status, updated_at);
