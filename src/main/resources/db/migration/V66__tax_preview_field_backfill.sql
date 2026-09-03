ALTER TABLE tax_document ADD COLUMN preview_attempts smallint NOT NULL DEFAULT 0;
ALTER TABLE tax_document ADD COLUMN preview_retry_at timestamptz NOT NULL DEFAULT now();
