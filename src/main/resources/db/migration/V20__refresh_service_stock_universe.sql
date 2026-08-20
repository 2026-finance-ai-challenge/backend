DELETE FROM ingestion_job job
USING disclosure d
JOIN security s ON s.id = d.security_id
WHERE job.job_type IN (
    'DISCLOSURE_DOCUMENT',
    'DISCLOSURE_EMBEDDING',
    'DISCLOSURE_METADATA_EMBEDDING'
)
  AND job.business_key = d.receipt_number
  AND s.stock_code IN (
      '020560', '030200', '031310', '033130', '033830', '034120',
      '035760', '036030', '036420', '036460', '036630', '037560',
      '039290', '039340', '040300', '053210', '058400', '065530',
      '066790', '089590', '091810', '122450', '126560', '127710',
      '272450', '298690'
  );

DELETE FROM service_stock_universe
WHERE stock_code IN (
    '020560', '030200', '031310', '033130', '033830', '034120',
    '035760', '036030', '036420', '036460', '036630', '037560',
    '039290', '039340', '040300', '053210', '058400', '065530',
    '066790', '089590', '091810', '122450', '126560', '127710',
    '272450', '298690'
);
