ALTER TABLE translation_memory
    ADD COLUMN result_payload JSONB,
    ADD COLUMN request_context JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE translation_memory
    DROP CONSTRAINT translation_memory_ready_payload;

ALTER TABLE translation_memory
    ADD CONSTRAINT translation_memory_ready_payload CHECK (
        status <> 'READY'
        OR (
            (
                (translated_text IS NOT NULL AND btrim(translated_text) <> '')
                OR result_payload IS NOT NULL
            )
            AND model_id IS NOT NULL
            AND prompt_version IS NOT NULL
            AND generated_at IS NOT NULL
        )
    );

CREATE INDEX translation_memory_source_lookup_idx
    ON translation_memory (content_kind, source_hash, target_locale, translation_version);
