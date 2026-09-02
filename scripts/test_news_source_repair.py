"""명시적으로 지정한 로컬 격리 PostgreSQL에서 복구 계획을 검증한다. 운영 접속은 거부한다."""

import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

import psycopg
from psycopg.conninfo import conninfo_to_dict
from pydantic import SecretStr


def main():
    dsn = os.environ["NEWS_REPAIR_TEST_DSN"]
    config = conninfo_to_dict(dsn)
    if config.get("dbname") != "kart_repair_test" or config.get("host") != "127.0.0.1":
        raise ValueError("Only the explicitly named isolated localhost database is allowed")
    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location("repair", root / "scripts/apply_news_source_repair.py")
    repair = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(repair)
    repair.get_settings = lambda: SimpleNamespace(database_url=SecretStr(dsn))
    with psycopg.connect(dsn, autocommit=True) as connection:
        if connection.execute("SELECT to_regclass('news_article')").fetchone()[0]:
            raise ValueError("Test database must be empty")
        for migration in sorted((root / "src/main/resources/db/migration").glob("V*.sql"),
                                key=lambda path: int(path.name.split("__")[0][1:])):
            connection.execute(migration.read_text())
        # 지원 종목 카탈로그는 마이그레이션의 실 스키마를 사용하고 기사만 합성한다.
        ids = []
        for status in ["READY", "READY", "READY", "PROCESSING", "READY"]:
            cluster = connection.execute("""INSERT INTO news_cluster (id, signature_hash, normalized_title, created_at, updated_at)
                VALUES (gen_random_uuid(), encode(sha256(random()::text::bytea), 'hex'), 'test', now(), now()) RETURNING id""").fetchone()[0]
            article = connection.execute("""INSERT INTO news_article
                (id, cluster_id, provider, provider_article_id, original_title, original_body, english_title,
                 original_url, canonical_url, canonical_url_hash, content_availability, source_policy,
                 analysis_status, published_at, collected_at, title_source_hash)
                VALUES (gen_random_uuid(), %s, 'TEST', gen_random_uuid()::text, '삼성전자 실적', '기존 본문', 'Samsung earnings',
                 'https://example.com/test', 'https://example.com/test', encode(sha256(random()::text::bytea), 'hex'),
                 'FULL_ARTICLE', 'test', %s, now(), now(), repeat('a', 64)) RETURNING id""", (cluster, status)).fetchone()[0]
            connection.execute("""INSERT INTO news_article_security (article_id, security_id, match_confidence)
                SELECT %s, id, 0.95 FROM security WHERE stock_code = '005930'""", (article,))
            connection.execute("""INSERT INTO news_analysis_job (article_id, status, attempts, next_attempt_at, updated_at)
                VALUES (%s, %s, 1, now(), now())""", (article, status))
            ids.append(str(article))
        previous = hashlib.sha256("기존 본문".encode()).hexdigest()
        items = [dict(id=article, action=action, title="삼성전자 실적", previousBodyHash=previous,
                      previousStocks=["005930"], stocks=[] if action == "QUARANTINE" else ["000660"],
                      body=None if action == "QUARANTINE" else "검증한 새 본문", sourcePolicy="test_verified")
                 for article, action in zip(ids, ["RESTORE", "REMAP", "QUARANTINE", "RESTORE", "RESTORE"], strict=True)]
        items[-1]["previousBodyHash"] = "0" * 64
        with tempfile.TemporaryDirectory(prefix="kart-repair-test-") as directory:
            plan = Path(directory) / "plan.json"
            plan.write_text(json.dumps({"items": items}))
            digest = hashlib.sha256(plan.read_bytes()).hexdigest()
            backup = Path(directory) / "backup.jsonl"
            def run(*args):
                output = io.StringIO()
                with patch.object(sys, "argv", ["repair", "--plan", str(plan), *args]), contextlib.redirect_stdout(output):
                    repair.main()
                return json.loads(output.getvalue())
            dry = run()
            assert dry["counts"] == {"REMAP": 1, "RESTORE": 1, "QUARANTINE": 1, "stale": 1, "missing": 0, "busy": 1}, dry
            assert connection.execute("SELECT count(*) FROM news_article WHERE original_body = '기존 본문'").fetchone()[0] == 5
            applied = run("--apply", "--approved-sha256", digest, "--backup", str(backup))
            assert applied["counts"] == dry["counts"], applied
            assert backup.stat().st_mode & 0o777 == 0o600
            assert len(backup.read_text().splitlines()) == 3
            restored = connection.execute("SELECT original_body, analysis_status FROM news_article WHERE id = %s", (ids[0],)).fetchone()
            assert restored == ("검증한 새 본문", "PENDING"), restored
            quarantined = connection.execute("SELECT original_body, content_availability FROM news_article WHERE id = %s", (ids[2],)).fetchone()
            assert quarantined == (None, "UNAVAILABLE"), quarantined
            assert connection.execute("SELECT count(*) FROM news_article_security WHERE article_id = %s", (ids[2],)).fetchone()[0] == 0
            assert connection.execute("SELECT tgenabled FROM pg_trigger WHERE tgname = 'news_watchlist_notification_trigger'").fetchone()[0] == 'O'
            print("NEWS_REPAIR_INTEGRATION passed: readonly, restore, remap, quarantine, stale/busy skip, backup, trigger restoration")


if __name__ == "__main__":
    main()
