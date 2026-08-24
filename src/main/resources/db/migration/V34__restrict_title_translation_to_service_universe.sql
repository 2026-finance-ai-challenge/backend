DELETE FROM translation_memory memory
WHERE memory.content_kind = 'DISCLOSURE_TITLE'
  AND memory.target_locale = 'en'
  AND memory.translation_version = 'codex-disclosure-title-v1'
  AND NOT EXISTS (
      SELECT 1
      FROM disclosure disclosure
      JOIN security security ON security.id = disclosure.security_id
      JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
      WHERE disclosure.title_source_hash = memory.source_hash
  );
