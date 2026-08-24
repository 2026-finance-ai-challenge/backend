CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE translation_memory (
    id UUID PRIMARY KEY,
    content_kind VARCHAR(32) NOT NULL,
    source_locale VARCHAR(16) NOT NULL,
    target_locale VARCHAR(16) NOT NULL,
    translation_version VARCHAR(100) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    source_text TEXT NOT NULL,
    normalized_source_text TEXT NOT NULL,
    translated_text TEXT,
    status VARCHAR(16) NOT NULL,
    model_id VARCHAR(100),
    prompt_version VARCHAR(100),
    generated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT translation_memory_content_kind CHECK (
        content_kind IN ('DISCLOSURE_TITLE', 'NEWS_TITLE', 'NEWS_NARRATIVE', 'DISCLOSURE_SECTION')
    ),
    CONSTRAINT translation_memory_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')
    ),
    CONSTRAINT translation_memory_source_hash CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT translation_memory_ready_payload CHECK (
        status <> 'READY'
        OR (
            translated_text IS NOT NULL
            AND btrim(translated_text) <> ''
            AND model_id IS NOT NULL
            AND prompt_version IS NOT NULL
            AND generated_at IS NOT NULL
        )
    ),
    CONSTRAINT translation_memory_identity UNIQUE (
        content_kind, source_hash, target_locale, translation_version
    )
);

CREATE INDEX translation_memory_status_idx
    ON translation_memory (content_kind, status, updated_at, id);

CREATE TABLE translation_job (
    translation_memory_id UUID PRIMARY KEY
        REFERENCES translation_memory (id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(100),
    last_error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT translation_job_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')
    ),
    CONSTRAINT translation_job_attempts CHECK (attempts BETWEEN 0 AND 10)
);

CREATE INDEX translation_job_claim_idx
    ON translation_job (available_at, updated_at, translation_memory_id)
    WHERE status = 'PENDING';

ALTER TABLE disclosure
    ADD COLUMN title_source_hash CHAR(64);

UPDATE disclosure
SET title_source_hash = encode(
    digest(regexp_replace(btrim(title_ko), '[[:space:]]+', ' ', 'g'), 'sha256'),
    'hex'
);

ALTER TABLE disclosure
    ALTER COLUMN title_source_hash SET NOT NULL,
    ADD CONSTRAINT disclosure_title_source_hash_format CHECK (
        title_source_hash ~ '^[0-9a-f]{64}$'
    );

CREATE INDEX disclosure_title_source_hash_idx ON disclosure (title_source_hash);

INSERT INTO translation_memory (
    id, content_kind, source_locale, target_locale, translation_version,
    source_hash, source_text, normalized_source_text, status,
    created_at, updated_at
)
SELECT gen_random_uuid(), 'DISCLOSURE_TITLE', 'ko', 'en',
       'codex-disclosure-title-v1', title_source_hash,
       min(title_ko),
       regexp_replace(btrim(min(title_ko)), '[[:space:]]+', ' ', 'g'),
       'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM disclosure
GROUP BY title_source_hash
ON CONFLICT (content_kind, source_hash, target_locale, translation_version) DO NOTHING;

INSERT INTO translation_job (
    translation_memory_id, status, attempts, available_at, created_at, updated_at
)
SELECT memory.id, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM translation_memory memory
WHERE memory.content_kind = 'DISCLOSURE_TITLE'
  AND memory.target_locale = 'en'
  AND memory.translation_version = 'codex-disclosure-title-v1'
  AND memory.status <> 'READY'
ON CONFLICT (translation_memory_id) DO NOTHING;
