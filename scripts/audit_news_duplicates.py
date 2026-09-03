"""운영 AI 컨테이너에서 실행하는 읽기 전용 뉴스 중복 점검. 원문 전문은 출력하지 않는다."""

import collections
import datetime as dt
import hashlib
import json
import re

import psycopg
from psycopg.rows import dict_row
from k_market_ai.core.config import get_settings


def normalize(value):
    return re.sub(r"\s+", " ", re.sub(r"[^0-9a-z가-힣 ]+", " ", (value or "").lower())).strip()


def tokens(value):
    return {word for word in normalize(value).split() if len(word) >= 2}


def similarity(left, right):
    if not left or not right:
        return 0.0
    common = len(left & right)
    jaccard = common / len(left | right)
    if min(len(left), len(right)) < 4:
        return jaccard
    return max(jaccard, .65 * common / min(len(left), len(right)) + .35 * 2 * common / (len(left) + len(right)))


settings = get_settings()
with psycopg.connect(settings.database_url.get_secret_value(), row_factory=dict_row) as connection:
    connection.execute("SET TRANSACTION READ ONLY")
    connection.execute("SET LOCAL statement_timeout = '30s'")
    totals = connection.execute("""
        SELECT count(*) AS retained_articles, count(DISTINCT cluster_id) AS retained_clusters,
          count(*) FILTER (WHERE collected_at >= now() - interval '24 hours') AS collected_24h,
          count(*) FILTER (WHERE collected_at >= now() - interval '7 days') AS collected_7d,
          count(*) FILTER (WHERE original_body IS NULL OR original_body = '') AS missing_body,
          count(DISTINCT canonical_url_hash) AS unique_urls,
          count(DISTINCT provider_article_id) AS unique_provider_ids,
          count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM news_article_security s WHERE s.article_id = news_article.id)) AS unmapped
        FROM news_article
    """).fetchone()
    rows = connection.execute("""
        SELECT id::text, cluster_id::text, original_title, original_excerpt, original_body,
          publisher, published_at, collected_at
        FROM news_article WHERE collected_at >= now() - interval '7 days'
        ORDER BY collected_at DESC LIMIT 20000
    """).fetchall()
    connection.rollback()

profiles = [(normalize(row['original_title']), tokens(row['original_title']), tokens(row['original_excerpt']), tokens(row['original_body'])) for row in rows]
body_groups = collections.defaultdict(list)
title_groups = collections.defaultdict(list)
index = collections.defaultdict(list)
for position, row in enumerate(rows):
    title_groups[profiles[position][0]].append(position)
    body = normalize(row['original_body'])
    if len(body) >= 200:
        body_groups[hashlib.sha256(body.encode()).hexdigest()].append(position)
    for token in profiles[position][1]:
        index[token].append(position)


def example(indices):
    return [{key: rows[i][key] for key in ('id', 'cluster_id', 'original_title', 'publisher', 'published_at')} for i in indices]


exact_body = [group for group in body_groups.values() if len(group) > 1]
exact_title = [group for group in title_groups.values() if len(group) > 1]
near = []
examined = 0
for i, row in enumerate(rows):
    candidates = set()
    for token in profiles[i][1]:
        if len(index[token]) <= 500:
            candidates.update(index[token])
    for j in candidates:
        if j <= i or rows[j]['cluster_id'] == row['cluster_id']:
            continue
        hours = abs((rows[j]['published_at'] - row['published_at']).total_seconds()) / 3600
        if hours > 36:
            continue
        examined += 1
        title_score = similarity(profiles[i][1], profiles[j][1])
        if title_score < .55:
            continue
        body_a, body_b = profiles[i][3], profiles[j][3]
        body_dice = 2 * len(body_a & body_b) / max(1, len(body_a) + len(body_b))
        excerpt_score = similarity(profiles[i][2], profiles[j][2])
        if body_dice >= .85 or (title_score >= .92 and excerpt_score >= .65):
            near.append({'title_score': round(title_score, 4), 'excerpt_score': round(excerpt_score, 4), 'body_dice': round(body_dice, 4), 'hours': round(hours, 2), 'articles': example([i, j])})

near.sort(key=lambda pair: (pair['body_dice'], pair['title_score']), reverse=True)
report = {
    'audited_at': dt.datetime.now(dt.timezone.utc), 'read_only': True, 'totals': totals,
    'sampled_7d': len(rows), 'sample_truncated': totals['collected_7d'] > len(rows),
    'exact_normalized_body_groups': len(exact_body),
    'exact_body_cross_cluster_groups': sum(len({rows[i]['cluster_id'] for i in g}) > 1 for g in exact_body),
    'exact_normalized_title_groups': len(exact_title),
    'exact_title_cross_cluster_groups': sum(len({rows[i]['cluster_id'] for i in g}) > 1 for g in exact_title),
    'candidate_comparisons': examined, 'near_duplicate_candidate_pairs': len(near),
    'near_duplicate_candidates': near[:30],
    'exact_body_examples': [example(g) for g in exact_body[:10]],
    'exact_title_examples': [example(g) for g in exact_title[:10]],
    'note': '유사 후보는 검토 대상이며 같은 사건의 후속 보도까지 중복으로 확정하지 않는다.',
}
print(json.dumps(report, ensure_ascii=False, indent=2, default=str))
