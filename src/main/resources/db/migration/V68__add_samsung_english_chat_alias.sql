INSERT INTO stock_alias (security_id, alias, normalized_alias, locale)
SELECT id, 'Samsung', 'samsung', 'en'
FROM security WHERE stock_code = '005930'
ON CONFLICT (security_id, normalized_alias) DO NOTHING;
