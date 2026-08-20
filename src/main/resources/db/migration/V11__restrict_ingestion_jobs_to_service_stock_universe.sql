DELETE FROM ingestion_job job
WHERE job.job_type IN (
    'DISCLOSURE_DOCUMENT',
    'DISCLOSURE_EMBEDDING',
    'DISCLOSURE_METADATA_EMBEDDING'
)
  AND EXISTS (
      SELECT 1
      FROM disclosure
      WHERE disclosure.receipt_number = job.business_key
  )
  AND NOT EXISTS (
      SELECT 1
      FROM disclosure
      JOIN security ON security.id = disclosure.security_id
      JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
      WHERE disclosure.receipt_number = job.business_key
        AND security.active
        AND security.common_stock
  );

DELETE FROM disclosure disclosure_to_delete
WHERE NOT EXISTS (
    SELECT 1
    FROM security
    JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
    WHERE security.id = disclosure_to_delete.security_id
      AND security.active
      AND security.common_stock
);

DELETE FROM security security_to_delete
WHERE NOT EXISTS (
    SELECT 1
    FROM service_stock_universe universe
    WHERE universe.stock_code = security_to_delete.stock_code
      AND security_to_delete.active
      AND security_to_delete.common_stock
);

DELETE FROM issuer issuer_to_delete
WHERE NOT EXISTS (
    SELECT 1
    FROM security
    JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
    WHERE security.issuer_id = issuer_to_delete.id
      AND security.active
      AND security.common_stock
);
