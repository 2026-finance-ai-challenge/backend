CREATE INDEX disclosure_security_display_ready_idx
    ON disclosure (security_id, filed_date DESC, detected_at DESC, receipt_number DESC)
    WHERE document_status = 'READY'
      AND event_type IS NOT NULL
      AND sentiment IS NOT NULL
      AND importance IS NOT NULL
      AND market_impact IS NOT NULL;
