CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE issuer (
    id UUID PRIMARY KEY,
    dart_corp_code VARCHAR(8) NOT NULL UNIQUE,
    name_ko VARCHAR(200) NOT NULL,
    name_en VARCHAR(300),
    corporation_class CHAR(1),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT issuer_dart_corp_code_format CHECK (dart_corp_code ~ '^[0-9]{8}$'),
    CONSTRAINT issuer_corporation_class_value CHECK (
        corporation_class IS NULL OR corporation_class IN ('Y', 'K', 'N', 'E')
    )
);

CREATE TABLE security (
    id UUID PRIMARY KEY,
    issuer_id UUID NOT NULL REFERENCES issuer (id),
    stock_code VARCHAR(6) NOT NULL UNIQUE,
    market VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT security_stock_code_format CHECK (stock_code ~ '^[0-9]{6}$'),
    CONSTRAINT security_market_value CHECK (market IN ('KOSPI', 'KOSDAQ', 'KONEX', 'OTHER', 'UNKNOWN'))
);

CREATE INDEX security_issuer_id_idx ON security (issuer_id);

CREATE TABLE disclosure (
    id UUID PRIMARY KEY,
    receipt_number VARCHAR(14) NOT NULL UNIQUE,
    issuer_id UUID NOT NULL REFERENCES issuer (id),
    security_id UUID REFERENCES security (id),
    disclosure_type CHAR(1) NOT NULL,
    title_ko VARCHAR(500) NOT NULL,
    submitter VARCHAR(200) NOT NULL,
    filed_date DATE NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    official_url VARCHAR(500) NOT NULL,
    remark VARCHAR(30) NOT NULL DEFAULT '',
    correction BOOLEAN NOT NULL DEFAULT FALSE,
    document_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT disclosure_receipt_number_format CHECK (receipt_number ~ '^[0-9]{14}$'),
    CONSTRAINT disclosure_type_value CHECK (disclosure_type BETWEEN 'A' AND 'I'),
    CONSTRAINT disclosure_document_status_value CHECK (document_status IN ('PENDING', 'READY', 'FAILED'))
);

CREATE INDEX disclosure_list_idx ON disclosure (filed_date DESC, receipt_number DESC);
CREATE INDEX disclosure_security_list_idx ON disclosure (security_id, filed_date DESC, receipt_number DESC);
CREATE INDEX disclosure_type_list_idx ON disclosure (disclosure_type, filed_date DESC, receipt_number DESC);

CREATE TABLE disclosure_document (
    id UUID PRIMARY KEY,
    disclosure_id UUID NOT NULL REFERENCES disclosure (id) ON DELETE CASCADE,
    source_filename VARCHAR(500) NOT NULL,
    version_no INTEGER NOT NULL,
    is_current BOOLEAN NOT NULL,
    content_hash CHAR(64) NOT NULL,
    body_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT disclosure_document_version_positive CHECK (version_no > 0),
    CONSTRAINT disclosure_document_hash_format CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT disclosure_document_version UNIQUE (disclosure_id, source_filename, version_no),
    CONSTRAINT disclosure_document_content UNIQUE (disclosure_id, source_filename, content_hash)
);

CREATE INDEX disclosure_document_disclosure_id_idx ON disclosure_document (disclosure_id);
CREATE UNIQUE INDEX disclosure_document_current_idx
    ON disclosure_document (disclosure_id, source_filename)
    WHERE is_current;

CREATE TABLE disclosure_section (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES disclosure_document (id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    section_kind VARCHAR(16) NOT NULL,
    heading VARCHAR(500),
    text_content TEXT NOT NULL,
    table_data JSONB,
    CONSTRAINT disclosure_section_ordinal_nonnegative CHECK (ordinal >= 0),
    CONSTRAINT disclosure_section_kind_value CHECK (section_kind IN ('TITLE', 'TEXT', 'TABLE')),
    CONSTRAINT disclosure_section_order UNIQUE (document_id, ordinal)
);

CREATE INDEX disclosure_section_document_id_idx ON disclosure_section (document_id, ordinal);

CREATE TABLE ingestion_job (
    id UUID PRIMARY KEY,
    job_type VARCHAR(32) NOT NULL,
    business_key VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(100),
    last_error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ingestion_job_status_value CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ingestion_job_attempts_nonnegative CHECK (attempts >= 0),
    CONSTRAINT ingestion_job_business_key UNIQUE (job_type, business_key)
);

CREATE INDEX ingestion_job_claim_idx ON ingestion_job (status, available_at, created_at);

CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
