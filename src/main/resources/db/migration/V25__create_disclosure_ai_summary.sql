ALTER TABLE disclosure
    ADD COLUMN filing_family_key VARCHAR(500);

ALTER TABLE disclosure
    ADD COLUMN correction_of_id UUID REFERENCES disclosure (id) ON DELETE SET NULL;

UPDATE disclosure
SET filing_family_key = btrim(
    regexp_replace(
        title_ko,
        '^[[:space:]]*(\[(기재정정|첨부정정|정정)\]|(기재정정|첨부정정|정정))[[:space:]]*',
        ''
    )
);

ALTER TABLE disclosure
    ALTER COLUMN filing_family_key SET NOT NULL;

WITH ordered_versions AS (
    SELECT id,
           correction,
           lag(id) OVER (
               PARTITION BY issuer_id, filing_family_key
               ORDER BY receipt_number
           ) AS previous_id
    FROM disclosure
)
UPDATE disclosure target
SET correction_of_id = ordered_versions.previous_id
FROM ordered_versions
WHERE target.id = ordered_versions.id
  AND ordered_versions.correction;

CREATE INDEX disclosure_version_family_idx
    ON disclosure (issuer_id, filing_family_key, receipt_number);

CREATE TABLE disclosure_ai_summary (
    disclosure_id UUID PRIMARY KEY REFERENCES disclosure (id) ON DELETE CASCADE,
    content_version_hash CHAR(64) NOT NULL,
    what_summary VARCHAR(3000),
    why_summary VARCHAR(3000),
    impact_summary VARCHAR(3000),
    source_section_ids UUID[] NOT NULL DEFAULT ARRAY[]::UUID[],
    sufficient_evidence BOOLEAN NOT NULL,
    refusal_reason VARCHAR(1000),
    model_id VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(100) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT disclosure_ai_summary_hash_format CHECK (
        content_version_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT disclosure_ai_summary_evidence_required CHECK (
        NOT sufficient_evidence OR cardinality(source_section_ids) > 0
    )
);

CREATE INDEX disclosure_ai_summary_generated_idx
    ON disclosure_ai_summary (generated_at DESC);
