-- 완료 작업을 제외한 작은 인덱스에서 최신 공시 임베딩 작업을 바로 선점한다.
CREATE INDEX ingestion_job_embedding_pending_idx
    ON ingestion_job (business_key DESC)
    INCLUDE (id, available_at, locked_at)
    WHERE job_type = 'DISCLOSURE_EMBEDDING'
      AND status IN ('PENDING', 'PROCESSING');
