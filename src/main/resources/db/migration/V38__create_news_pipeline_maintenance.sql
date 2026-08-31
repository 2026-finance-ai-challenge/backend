CREATE TABLE news_pipeline_maintenance (
    version VARCHAR(100) PRIMARY KEY,
    retained_count INTEGER NOT NULL,
    deleted_count INTEGER NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT news_pipeline_maintenance_counts CHECK (
        retained_count >= 0 AND deleted_count >= 0
    )
);
