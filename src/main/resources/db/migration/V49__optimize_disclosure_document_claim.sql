-- 종목별 순번이 반영된 우선순위로 원문 작업을 즉시 선점한다.
CREATE INDEX ingestion_job_document_priority_claim_v2_idx
    ON ingestion_job (priority, available_at, created_at)
    WHERE job_type = 'DISCLOSURE_DOCUMENT'
      AND status IN ('PENDING', 'PROCESSING');
