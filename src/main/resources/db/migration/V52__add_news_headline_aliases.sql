INSERT INTO stock_alias (security_id, alias, normalized_alias, locale)
SELECT security.id, alias.value, LOWER(alias.value), 'ko'
FROM security
CROSS JOIN LATERAL (
    VALUES
        ('삼전'),
        ('삼전닉스')
) AS alias(value)
WHERE security.stock_code = '005930'
ON CONFLICT (security_id, normalized_alias) DO NOTHING;

INSERT INTO stock_alias (security_id, alias, normalized_alias, locale)
SELECT security.id, alias.value, LOWER(alias.value), 'ko'
FROM security
CROSS JOIN LATERAL (
    VALUES
        ('하닉'),
        ('하이닉스'),
        ('삼전닉스')
) AS alias(value)
WHERE security.stock_code = '000660'
ON CONFLICT (security_id, normalized_alias) DO NOTHING;
