"""검토된 기사별 복구 계획. 기본은 읽기 전용이며 적용 시 원본 백업과 해시 대조를 요구한다."""

import argparse
import hashlib
import json
import os
from pathlib import Path

import psycopg
from psycopg.rows import dict_row
from k_market_ai.core.config import get_settings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--approved-sha256")
    parser.add_argument("--backup")
    args = parser.parse_args()
    raw = Path(args.plan).read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    plan = json.loads(raw)
    items = plan["items"]
    if not items or len(items) > 5000 or len({item["id"] for item in items}) != len(items):
        raise ValueError("Invalid repair plan size or duplicate article IDs")
    for item in items:
        if item["action"] not in {"REMAP", "RESTORE", "QUARANTINE"}:
            raise ValueError("Unknown repair action")
        if item["action"] != "QUARANTINE" and (not item["body"] or not item["stocks"]):
            raise ValueError("Verified body and stock associations are required")
        if item["action"] == "QUARANTINE" and item["stocks"]:
            raise ValueError("Quarantined sources must not retain stock associations")
    if args.apply and (args.approved_sha256 != digest or not args.backup):
        raise ValueError("Application requires the reviewed plan hash and a new backup path")
    backup = None
    if args.apply:
        backup_path = Path(args.backup)
        if not backup_path.is_absolute():
            raise ValueError("Backup path must be absolute")
        backup = os.fdopen(os.open(backup_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w")
    counts = {"REMAP": 0, "RESTORE": 0, "QUARANTINE": 0, "stale": 0, "missing": 0, "busy": 0}
    try:
        with psycopg.connect(get_settings().database_url.get_secret_value(), row_factory=dict_row) as connection:
            with connection.transaction():
                if not args.apply:
                    connection.execute("SET TRANSACTION READ ONLY")
                connection.execute("SET LOCAL statement_timeout = '20s'")
                connection.execute("SET LOCAL lock_timeout = '3s'")
                supported = {row["stock_code"] for row in connection.execute("SELECT stock_code FROM service_stock_universe")}
                if any(not set(item["stocks"]).issubset(supported) for item in items):
                    raise ValueError("Plan contains an unsupported stock")
                if args.apply:
                    # 검토한 기사만 백업하며 전체 알림·사용자 데이터는 읽지 않는다.
                    connection.execute("LOCK TABLE news_article_security IN SHARE ROW EXCLUSIVE MODE")
                    connection.execute("ALTER TABLE news_article_security DISABLE TRIGGER news_watchlist_notification_trigger")
                for item in items:
                    row = connection.execute(
                        "SELECT * FROM news_article WHERE id = %s" + (" FOR UPDATE" if args.apply else ""),
                        (item["id"],),
                    ).fetchone()
                    if not row:
                        counts["missing"] += 1
                        continue
                    if row["analysis_status"] == "PROCESSING":
                        counts["busy"] += 1
                        continue
                    old_body_hash = hashlib.sha256((row["original_body"] or "").encode()).hexdigest()
                    links = connection.execute("""
                        SELECT link.*, s.stock_code FROM news_article_security link
                        JOIN security s ON s.id = link.security_id WHERE link.article_id = %s
                    """, (item["id"],)).fetchall()
                    if (old_body_hash != item["previousBodyHash"] or row["original_title"] != item["title"]
                            or {link["stock_code"] for link in links} != set(item["previousStocks"])):
                        counts["stale"] += 1
                        continue
                    counts[item["action"]] += 1
                    if not args.apply:
                        continue
                    memories = connection.execute("""
                        SELECT * FROM translation_memory
                        WHERE content_kind = 'NEWS_NARRATIVE' AND request_context ->> 'article_id' = %s
                    """, (item["id"],)).fetchall()
                    translation_jobs = connection.execute("""
                        SELECT job.* FROM translation_job job JOIN translation_memory memory
                        ON memory.id = job.translation_memory_id
                        WHERE memory.content_kind = 'NEWS_NARRATIVE' AND memory.request_context ->> 'article_id' = %s
                    """, (item["id"],)).fetchall()
                    analysis_jobs = connection.execute("SELECT * FROM news_analysis_job WHERE article_id = %s", (item["id"],)).fetchall()
                    backup.write(json.dumps({"planSha256": digest, "article": row, "links": links,
                                             "translationMemories": memories, "translationJobs": translation_jobs,
                                             "analysisJobs": analysis_jobs}, default=str, ensure_ascii=False) + "\n")
                    backup.flush()
                    os.fsync(backup.fileno())
                    if item["action"] != "REMAP":
                        available = item["action"] == "RESTORE"
                        connection.execute("""
                            UPDATE translation_job job SET status = 'FAILED', locked_at = NULL,
                                locked_by = NULL, last_error_code = 'SOURCE_REVISED', updated_at = now()
                            FROM translation_memory memory WHERE memory.id = job.translation_memory_id
                                AND memory.content_kind = 'NEWS_NARRATIVE'
                                AND memory.request_context ->> 'article_id' = %s
                        """, (item["id"],))
                        connection.execute("""
                            UPDATE translation_memory SET status = 'FAILED', updated_at = now()
                            WHERE content_kind = 'NEWS_NARRATIVE' AND request_context ->> 'article_id' = %s
                        """, (item["id"],))
                        connection.execute("""
                            UPDATE news_article SET original_body = %s, content_availability = %s,
                                source_policy = %s, english_body = NULL, what_summary = NULL,
                                why_summary = NULL, impact_summary = NULL, what_summary_ko = NULL,
                                why_summary_ko = NULL, impact_summary_ko = NULL, analysis_status = %s,
                                event_type = NULL, sentiment = NULL, importance = NULL, analyzed_at = NULL WHERE id = %s
                        """, (item["body"] if available else None, "FULL_ARTICLE" if available else "UNAVAILABLE",
                              item["sourcePolicy"] if available else "quarantined_source_review_v1",
                              "PENDING" if available else "FAILED", item["id"]))
                        connection.execute("""
                            INSERT INTO news_analysis_job (article_id, status, attempts, next_attempt_at, last_error_code, updated_at)
                            VALUES (%s, %s, 0, now(), 'SOURCE_REVISED', now())
                            ON CONFLICT (article_id) DO UPDATE SET status = EXCLUDED.status, attempts = 0,
                                next_attempt_at = now(), locked_at = NULL, last_error_code = 'SOURCE_REVISED', updated_at = now()
                        """, (item["id"], "PENDING" if available else "FAILED"))
                    connection.execute("""
                        DELETE FROM news_article_security link USING security s
                        WHERE link.security_id = s.id AND link.article_id = %s
                            AND NOT (s.stock_code = ANY(%s))
                    """, (item["id"], item["stocks"]))
                    for code in item["stocks"]:
                        connection.execute("""
                            INSERT INTO news_article_security (article_id, security_id, match_confidence)
                            SELECT %s, s.id, 0.95 FROM security s
                            JOIN service_stock_universe u ON u.stock_code = s.stock_code
                            WHERE s.stock_code = %s ON CONFLICT DO NOTHING
                        """, (item["id"], code))
                if args.apply:
                    connection.execute("ALTER TABLE news_article_security ENABLE TRIGGER news_watchlist_notification_trigger")
        print(json.dumps({"apply": args.apply, "planSha256": digest, "counts": counts}))
    finally:
        if backup:
            backup.close()


if __name__ == "__main__":
    main()
