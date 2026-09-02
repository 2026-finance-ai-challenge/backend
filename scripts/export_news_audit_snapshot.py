"""읽기 전용 공개 뉴스·종목 연결 스냅샷. 출력은 로컬 감사 파일로 보관한다."""

import datetime as dt
import json

import psycopg
from psycopg.rows import dict_row
from k_market_ai.core.config import get_settings

with psycopg.connect(get_settings().database_url.get_secret_value(), row_factory=dict_row) as connection:
    connection.execute("SET TRANSACTION READ ONLY")
    connection.execute("SET LOCAL statement_timeout = '30s'")
    stocks = connection.execute("""
        SELECT s.stock_code AS "stockCode", i.name_ko AS "nameKo", i.name_en AS "nameEn", s.market,
            ARRAY(SELECT a.alias FROM stock_alias a WHERE a.security_id = s.id) AS aliases
        FROM security s JOIN issuer i ON i.id = s.issuer_id
        JOIN service_stock_universe u ON u.stock_code = s.stock_code ORDER BY s.stock_code
    """).fetchall()
    articles = connection.execute("""
        SELECT n.id::text, n.original_title AS title, n.original_body AS body, n.publisher, n.original_url AS "originalUrl",
            ARRAY(SELECT s.stock_code FROM news_article_security link
                JOIN security s ON s.id = link.security_id WHERE link.article_id = n.id) AS stocks
        FROM news_article n ORDER BY n.id
    """).fetchall()
    connection.rollback()
print(json.dumps({"auditedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                  "stocks": stocks, "articles": articles}, ensure_ascii=False))
