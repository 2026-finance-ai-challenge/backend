DROP INDEX IF EXISTS disclosure_chunk_embedding_idx;
DROP INDEX IF EXISTS disclosure_document_embedding_vector_idx;
DROP INDEX IF EXISTS disclosure_chunk_section_ids_idx;
DROP INDEX IF EXISTS disclosure_chunk_scope_idx;

CREATE INDEX disclosure_chunk_scope_search_idx
    ON disclosure_chunk (disclosure_id, is_current, embedding_model, chunk_index);
