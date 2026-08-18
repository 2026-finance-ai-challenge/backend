ALTER TABLE disclosure
    ADD COLUMN index_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE disclosure
    ADD CONSTRAINT disclosure_index_status_value
        CHECK (index_status IN ('PENDING', 'READY', 'FAILED'));

CREATE TABLE disclosure_chunk (
    id UUID PRIMARY KEY,
    disclosure_id UUID NOT NULL REFERENCES disclosure (id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES disclosure_document (id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    section_ids UUID[] NOT NULL,
    first_ordinal INTEGER NOT NULL,
    last_ordinal INTEGER NOT NULL,
    heading VARCHAR(500),
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    embedding VECTOR(384) NOT NULL,
    embedding_model VARCHAR(200) NOT NULL,
    embedding_dimensions INTEGER NOT NULL,
    chunker_version VARCHAR(50) NOT NULL,
    is_current BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT disclosure_chunk_index_nonnegative CHECK (chunk_index >= 0),
    CONSTRAINT disclosure_chunk_ordinal_range CHECK (
        first_ordinal >= 0 AND last_ordinal >= first_ordinal
    ),
    CONSTRAINT disclosure_chunk_section_ids_nonempty CHECK (cardinality(section_ids) > 0),
    CONSTRAINT disclosure_chunk_content_nonempty CHECK (length(btrim(content)) > 0),
    CONSTRAINT disclosure_chunk_hash_format CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT disclosure_chunk_dimensions CHECK (embedding_dimensions = 384),
    CONSTRAINT disclosure_chunk_version UNIQUE (
        document_id, chunk_index, content_hash, embedding_model, chunker_version
    )
);

CREATE INDEX disclosure_chunk_scope_idx
    ON disclosure_chunk (disclosure_id, is_current, document_id, chunk_index);

CREATE INDEX disclosure_chunk_section_ids_idx
    ON disclosure_chunk USING GIN (section_ids);

CREATE INDEX disclosure_chunk_embedding_idx
    ON disclosure_chunk USING HNSW (embedding vector_cosine_ops)
    WHERE is_current;

INSERT INTO ingestion_job (
    id, job_type, business_key, status, attempts, available_at, created_at, updated_at
)
SELECT
    gen_random_uuid(), 'DISCLOSURE_EMBEDDING', receipt_number, 'PENDING', 0,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM disclosure
WHERE document_status = 'READY'
ON CONFLICT (job_type, business_key) DO NOTHING;
