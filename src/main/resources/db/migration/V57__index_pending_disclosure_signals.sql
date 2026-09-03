-- 접수번호는 접수일로 시작하므로 완료 공시를 조인하지 않고 최신 대기 작업을 선점한다.
CREATE INDEX ingestion_job_signal_pending_idx
    ON ingestion_job (business_key DESC)
    WHERE job_type = 'DISCLOSURE_SIGNAL' AND status IN ('PENDING', 'PROCESSING');
