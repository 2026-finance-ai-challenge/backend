ALTER TABLE disclosure
    ADD COLUMN event_type VARCHAR(100),
    ADD COLUMN sentiment VARCHAR(16),
    ADD COLUMN importance VARCHAR(16),
    ADD COLUMN market_impact VARCHAR(16),
    ADD COLUMN market_impact_importance VARCHAR(16),
    ADD COLUMN market_impact_score NUMERIC(5,4),
    ADD COLUMN event_confidence NUMERIC(5,4),
    ADD COLUMN sentiment_confidence NUMERIC(5,4),
    ADD COLUMN importance_confidence NUMERIC(5,4),
    ADD COLUMN market_impact_confidence NUMERIC(5,4),
    ADD COLUMN analysis_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN analysis_model_id VARCHAR(100),
    ADD COLUMN analyzed_at TIMESTAMPTZ,
    ADD CONSTRAINT disclosure_sentiment_value CHECK (
        sentiment IS NULL OR sentiment IN ('POSITIVE', 'NEUTRAL', 'NEGATIVE', 'MIXED')
    ),
    ADD CONSTRAINT disclosure_importance_value CHECK (
        importance IS NULL OR importance IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    ADD CONSTRAINT disclosure_market_impact_value CHECK (
        market_impact IS NULL OR market_impact IN ('POSITIVE', 'NEUTRAL', 'NEGATIVE', 'UNCERTAIN')
    ),
    ADD CONSTRAINT disclosure_analysis_status_value CHECK (
        analysis_status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')
    ),
    ADD CONSTRAINT disclosure_analysis_confidence_range CHECK (
        (event_confidence IS NULL OR event_confidence BETWEEN 0 AND 1)
        AND (sentiment_confidence IS NULL OR sentiment_confidence BETWEEN 0 AND 1)
        AND (importance_confidence IS NULL OR importance_confidence BETWEEN 0 AND 1)
        AND (market_impact_confidence IS NULL OR market_impact_confidence BETWEEN 0 AND 1)
        AND (market_impact_score IS NULL OR market_impact_score BETWEEN 0 AND 1)
    ),
    ADD CONSTRAINT disclosure_analysis_ready_payload CHECK (
        analysis_status <> 'READY'
        OR (
            event_type IS NOT NULL AND btrim(event_type) <> ''
            AND sentiment IS NOT NULL
            AND importance IS NOT NULL
            AND market_impact IS NOT NULL
            AND market_impact_importance IS NOT NULL
            AND market_impact_score IS NOT NULL
            AND event_confidence IS NOT NULL
            AND sentiment_confidence IS NOT NULL
            AND importance_confidence IS NOT NULL
            AND market_impact_confidence IS NOT NULL
            AND analysis_model_id IS NOT NULL AND btrim(analysis_model_id) <> ''
            AND analyzed_at IS NOT NULL
        )
    );

CREATE INDEX disclosure_publish_ready_idx
    ON disclosure (filed_date DESC, receipt_number DESC)
    WHERE document_status = 'READY'
      AND index_status = 'READY'
      AND analysis_status = 'READY';

INSERT INTO ingestion_job (
    id, job_type, business_key, status, attempts,
    available_at, created_at, updated_at
)
SELECT gen_random_uuid(), 'DISCLOSURE_SIGNAL', disclosure.receipt_number,
       'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM disclosure
JOIN security ON security.id = disclosure.security_id
JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
WHERE disclosure.document_status = 'READY'
ON CONFLICT (job_type, business_key) DO NOTHING;
