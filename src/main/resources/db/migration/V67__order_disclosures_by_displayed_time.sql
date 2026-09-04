CREATE INDEX disclosure_display_order_idx
    ON disclosure (filed_date DESC, detected_at DESC, receipt_number DESC);
