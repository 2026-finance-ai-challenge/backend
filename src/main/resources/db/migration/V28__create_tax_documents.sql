CREATE TABLE tax_document (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    document_type VARCHAR(32) NOT NULL,
    expected_residency_country CHAR(2) NOT NULL,
    investor_type VARCHAR(16) NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(32) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    storage_key VARCHAR(96) NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL,
    progress SMALLINT NOT NULL DEFAULT 10,
    stage VARCHAR(48) NOT NULL DEFAULT 'QUEUED',
    detected_document_type VARCHAR(32),
    extracted_fields JSONB NOT NULL DEFAULT '{}'::jsonb,
    missing_required_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    issues JSONB NOT NULL DEFAULT '[]'::jsonb,
    ocr_confidence NUMERIC(5,4),
    tamper_risk NUMERIC(5,4),
    manual_review_required BOOLEAN NOT NULL DEFAULT TRUE,
    model_id VARCHAR(100),
    prompt_version VARCHAR(100),
    request_id VARCHAR(100),
    attempts SMALLINT NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(100),
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    purge_after TIMESTAMPTZ,
    purged_at TIMESTAMPTZ,
    CONSTRAINT tax_document_type_check CHECK (document_type IN (
        'RESIDENCY_CERTIFICATE', 'APOSTILLE', 'REDUCED_TAX_APPLICATION'
    )),
    CONSTRAINT tax_document_investor_type_check CHECK (investor_type IN ('INDIVIDUAL', 'CORPORATE')),
    CONSTRAINT tax_document_status_check CHECK (status IN (
        'PROCESSING', 'VERIFIED', 'REVIEW_REQUIRED', 'REJECTED', 'FAILED'
    )),
    CONSTRAINT tax_document_country_check CHECK (expected_residency_country ~ '^[A-Z]{2}$'),
    CONSTRAINT tax_document_size_check CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    CONSTRAINT tax_document_progress_check CHECK (progress BETWEEN 0 AND 100)
);

CREATE UNIQUE INDEX uq_tax_document_active_content
    ON tax_document(user_id, document_type, sha256)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_tax_document_user_created
    ON tax_document(user_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_tax_document_queue
    ON tax_document(available_at, created_at)
    WHERE status = 'PROCESSING' AND deleted_at IS NULL;

CREATE TABLE tax_document_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES tax_document(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    action VARCHAR(48) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_tax_document_audit_document_time
    ON tax_document_audit(document_id, occurred_at DESC);
