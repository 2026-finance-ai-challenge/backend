CREATE INDEX ingestion_job_priority_claim_idx
    ON ingestion_job (job_type, attempts DESC, available_at, created_at);
