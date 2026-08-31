ALTER TABLE translation_job
    ADD COLUMN priority SMALLINT NOT NULL DEFAULT 100,
    ADD CONSTRAINT translation_job_priority_range CHECK (priority BETWEEN 0 AND 100);

CREATE INDEX translation_job_priority_claim_idx
    ON translation_job (priority, available_at, updated_at, translation_memory_id)
    WHERE status = 'PENDING';
