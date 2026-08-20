ALTER TABLE disclosure_document
    ALTER COLUMN body_text DROP NOT NULL,
    ADD COLUMN payload_zstd BYTEA,
    ADD COLUMN original_bytes BIGINT,
    ADD COLUMN compressed_bytes BIGINT;

ALTER TABLE disclosure_document
    ADD CONSTRAINT disclosure_document_payload_present CHECK (
        body_text IS NOT NULL OR payload_zstd IS NOT NULL
    ),
    ADD CONSTRAINT disclosure_document_original_bytes_positive CHECK (
        original_bytes IS NULL OR original_bytes > 0
    ),
    ADD CONSTRAINT disclosure_document_compressed_bytes_positive CHECK (
        compressed_bytes IS NULL OR compressed_bytes > 0
    );

DROP INDEX disclosure_chunk_embedding_idx;

ALTER TABLE disclosure_chunk
    ALTER COLUMN content DROP NOT NULL,
    ALTER COLUMN embedding TYPE HALFVEC(384) USING embedding::HALFVEC(384);

ALTER TABLE disclosure_chunk
    DROP CONSTRAINT disclosure_chunk_content_nonempty;

ALTER TABLE disclosure_chunk
    ADD CONSTRAINT disclosure_chunk_content_nonempty CHECK (
        content IS NULL OR length(btrim(content)) > 0
    );

CREATE INDEX disclosure_chunk_embedding_idx
    ON disclosure_chunk USING HNSW (embedding halfvec_cosine_ops)
    WHERE is_current;

CREATE TABLE disclosure_document_embedding (
    disclosure_id UUID PRIMARY KEY REFERENCES disclosure (id) ON DELETE CASCADE,
    embedding HALFVEC(384) NOT NULL,
    embedding_model VARCHAR(200) NOT NULL,
    embedding_dimensions INTEGER NOT NULL,
    source_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT disclosure_document_embedding_dimensions CHECK (embedding_dimensions = 384),
    CONSTRAINT disclosure_document_embedding_hash_format CHECK (
        source_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX disclosure_document_embedding_vector_idx
    ON disclosure_document_embedding USING HNSW (embedding halfvec_cosine_ops);

INSERT INTO ingestion_job (
    id, job_type, business_key, status, attempts, available_at, created_at, updated_at
)
SELECT
    gen_random_uuid(), 'DISCLOSURE_METADATA_EMBEDDING', disclosure.receipt_number,
    'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM disclosure
JOIN security ON security.id = disclosure.security_id
JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
WHERE security.active AND security.common_stock
ON CONFLICT (job_type, business_key) DO NOTHING;
