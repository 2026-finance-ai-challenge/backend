CREATE TABLE user_account (
    id UUID PRIMARY KEY,
    login_id VARCHAR(30) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nationality CHAR(2) NOT NULL,
    investor_type VARCHAR(16) NOT NULL,
    tax_verification_status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    terms_accepted_at TIMESTAMPTZ NOT NULL,
    privacy_accepted_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT user_account_login_id_format CHECK (login_id ~ '^[a-z0-9][a-z0-9._-]{3,29}$'),
    CONSTRAINT user_account_nationality_format CHECK (nationality ~ '^[A-Z]{2}$'),
    CONSTRAINT user_account_investor_type CHECK (investor_type IN ('INDIVIDUAL', 'CORPORATE')),
    CONSTRAINT user_account_tax_status CHECK (
        tax_verification_status IN ('NOT_STARTED', 'VERIFIED', 'REVIEW_REQUIRED', 'REJECTED')
    )
);

CREATE TABLE security_audit_event (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES user_account (id) ON DELETE SET NULL,
    event_type VARCHAR(48) NOT NULL,
    subject_type VARCHAR(32),
    subject_id VARCHAR(128),
    request_id VARCHAR(64),
    client_ip_hash CHAR(64) NOT NULL,
    user_agent_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX security_audit_event_user_created_idx
    ON security_audit_event (user_id, created_at DESC);
