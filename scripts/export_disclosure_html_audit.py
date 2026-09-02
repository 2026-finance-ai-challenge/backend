"""종목별 최신 구버전 공시 한 건의 압축 원문과 아카이브 경로를 읽기 전용으로 추출한다."""

import base64
import json
import psycopg
from psycopg.rows import dict_row
from k_market_ai.core.config import get_settings

with psycopg.connect(get_settings().database_url.get_secret_value(), row_factory=dict_row) as connection:
    connection.execute("SET TRANSACTION READ ONLY")
    connection.execute("SET LOCAL statement_timeout = '45s'")
    rows = connection.execute("""
        SELECT DISTINCT ON (s.stock_code) s.stock_code, d.receipt_number, doc.id, doc.content_hash,
            doc.source_filename, doc.parser_version, doc.payload_zstd,
            (SELECT jsonb_agg(jsonb_build_object('path', a.relative_path, 'kind', a.archive_kind, 'sha256', a.sha256))
             FROM disclosure_archive a WHERE a.disclosure_id = d.id AND a.archive_status = 'VERIFIED') AS archives
        FROM service_stock_universe u JOIN security s ON s.stock_code = u.stock_code
        JOIN disclosure d ON d.security_id = s.id
        JOIN disclosure_document doc ON doc.disclosure_id = d.id
        WHERE doc.is_current AND doc.parser_version <> 'opendart-html-v4'
            AND octet_length(doc.payload_zstd) < 1000000
        ORDER BY s.stock_code, d.filed_date DESC, d.receipt_number DESC, doc.id
    """).fetchall()
    for row in rows:
        row["payload_zstd"] = base64.b64encode(row["payload_zstd"]).decode()
    connection.rollback()
print(json.dumps(rows, default=str, ensure_ascii=False))
