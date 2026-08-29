package com.kmarket.navigator.backend.news.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.news.application.port.NewsRepository;
import com.kmarket.navigator.backend.news.domain.MarketImpact;
import com.kmarket.navigator.backend.news.domain.NewsAnalysis;
import com.kmarket.navigator.backend.news.domain.NewsAnalysisJob;
import com.kmarket.navigator.backend.news.domain.NewsAnalysisStatus;
import com.kmarket.navigator.backend.news.domain.NewsArticle;
import com.kmarket.navigator.backend.news.domain.NewsClusterAssignment;
import com.kmarket.navigator.backend.news.domain.NewsCollectionTarget;
import com.kmarket.navigator.backend.news.domain.NewsContentAvailability;
import com.kmarket.navigator.backend.news.domain.NewsCursor;
import com.kmarket.navigator.backend.news.domain.NewsDraft;
import com.kmarket.navigator.backend.news.domain.NewsDuplicateCandidate;
import com.kmarket.navigator.backend.news.domain.NewsImportance;
import com.kmarket.navigator.backend.news.domain.NewsPage;
import com.kmarket.navigator.backend.news.domain.NewsQuery;
import com.kmarket.navigator.backend.news.domain.NewsRanks;
import com.kmarket.navigator.backend.news.domain.NewsSentiment;
import com.kmarket.navigator.backend.news.domain.NewsSort;
import com.kmarket.navigator.backend.news.domain.NewsStockMapping;
import com.kmarket.navigator.backend.news.domain.RelatedStock;
import com.kmarket.navigator.backend.news.domain.TermReference;

@Repository
class JdbcNewsRepository implements NewsRepository {

	private final JdbcClient jdbcClient;

	JdbcNewsRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public NewsPage findPage(NewsQuery query) {
		String rank = rankExpression(query.sort());
		JdbcClient.StatementSpec statement = jdbcClient.sql("""
			SELECT article.*,
			       (SELECT COUNT(*) FROM news_article related
			        WHERE related.cluster_id = article.cluster_id) AS related_coverage_count
			FROM news_article article
			JOIN news_cluster story
			  ON story.id = article.cluster_id
			 AND story.representative_article_id = article.id
			WHERE (CAST(:query AS varchar) IS NULL
			       OR EXISTS (
			         SELECT 1 FROM news_article searchable
			         WHERE searchable.cluster_id = article.cluster_id
			           AND (searchable.original_title ILIKE '%%' || :query || '%%' ESCAPE '\\'
			             OR searchable.original_excerpt ILIKE '%%' || :query || '%%' ESCAPE '\\'
			             OR COALESCE(searchable.english_title, '') ILIKE '%%' || :query || '%%' ESCAPE '\\'
			             OR COALESCE(searchable.english_body, '') ILIKE '%%' || :query || '%%' ESCAPE '\\')
			       ))
			  AND (CAST(:stockCode AS varchar) IS NULL OR EXISTS (
			    SELECT 1
			    FROM news_article related_article
			    JOIN news_article_security article_security
			      ON article_security.article_id = related_article.id
			    JOIN security security_filter ON security_filter.id = article_security.security_id
			    WHERE related_article.cluster_id = article.cluster_id
			      AND security_filter.stock_code = :stockCode
			))
			  AND (CAST(:sentiment AS varchar) IS NULL OR article.sentiment = :sentiment)
			  AND (CAST(:importance AS varchar) IS NULL OR article.importance = :importance)
			  AND (CAST(:marketImpact AS varchar) IS NULL OR article.market_impact = :marketImpact)
			  AND (CAST(:marketImpactImportance AS varchar) IS NULL
			       OR article.market_impact_importance = :marketImpactImportance)
			  AND (CAST(:watchlistOnly AS boolean) = FALSE OR EXISTS (
			    SELECT 1
			    FROM news_article related_article
			    JOIN news_article_security watched_article
			      ON watched_article.article_id = related_article.id
			    JOIN watchlist_item watched
			      ON watched.security_id = watched_article.security_id
			    WHERE related_article.cluster_id = article.cluster_id
			      AND watched.user_id = :watchlistUserId
			  ))
			  AND (CAST(:fromTime AS timestamptz) IS NULL OR article.published_at >= :fromTime)
			  AND (CAST(:toTime AS timestamptz) IS NULL OR article.published_at <= :toTime)
			  AND (
			    CAST(:cursorRank AS numeric) IS NULL
			    OR %s < :cursorRank
			    OR (%s = :cursorRank AND article.published_at < :cursorTime)
			    OR (%s = :cursorRank AND article.published_at = :cursorTime AND article.id < :cursorId)
			  )
			ORDER BY %s DESC, article.published_at DESC, article.id DESC
			LIMIT :limit
			""".formatted(rank, rank, rank, rank));
		statement = nullable(statement, "query", escapeLike(query.query()), Types.VARCHAR);
		statement = nullable(statement, "stockCode", query.stockCode(), Types.VARCHAR);
		statement = nullable(
			statement,
			"sentiment",
			query.sentiment() == null ? null : query.sentiment().name(),
			Types.VARCHAR
		);
		statement = nullable(
			statement,
			"importance",
			query.importance() == null ? null : query.importance().name(),
			Types.VARCHAR
		);
		statement = nullable(
			statement,
			"marketImpact",
			query.marketImpact() == null ? null : query.marketImpact().name(),
			Types.VARCHAR
		);
		statement = nullable(
			statement,
			"marketImpactImportance",
			query.marketImpactImportance() == null ? null : query.marketImpactImportance().name(),
			Types.VARCHAR
		);
		statement = statement.param("watchlistOnly", query.watchlistOnly());
		statement = nullable(statement, "watchlistUserId", query.userId(), Types.OTHER);
		statement = nullable(statement, "fromTime", atUtc(query.from()), Types.TIMESTAMP_WITH_TIMEZONE);
		statement = nullable(statement, "toTime", atUtc(query.to()), Types.TIMESTAMP_WITH_TIMEZONE);
		statement = nullable(
			statement,
			"cursorRank",
			query.cursor() == null ? null : query.cursor().sortRank(),
			Types.NUMERIC
		);
		statement = nullable(
			statement,
			"cursorTime",
			query.cursor() == null ? null : atUtc(query.cursor().publishedAt()),
			Types.TIMESTAMP_WITH_TIMEZONE
		);
		statement = nullable(
			statement,
			"cursorId",
			query.cursor() == null ? null : query.cursor().id(),
			Types.OTHER
		);
		List<NewsArticle> fetched = new ArrayList<>(statement
			.param("limit", query.limit() + 1)
			.query(this::mapArticle)
			.list());
		boolean hasMore = fetched.size() > query.limit();
		if (hasMore) {
			fetched.removeLast();
		}
		List<NewsArticle> items = fetched.stream().map(this::withStocks).toList();
		String nextCursor = hasMore && !items.isEmpty()
			? new NewsCursor(
				NewsRanks.rank(items.getLast(), query.sort()),
				items.getLast().publishedAt(),
				items.getLast().id()
			).encode()
			: null;
		return new NewsPage(items, nextCursor);
	}

	private String escapeLike(String value) {
		return value == null ? null : value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	@Override
	public Optional<NewsArticle> findById(UUID articleId) {
		return jdbcClient.sql("""
			SELECT article.*,
			       (SELECT COUNT(*) FROM news_article related
			        WHERE related.cluster_id = article.cluster_id) AS related_coverage_count
			FROM news_article article
			WHERE article.id = :articleId
			""")
			.param("articleId", articleId)
			.query(this::mapArticle)
			.optional()
			.map(this::withStocks);
	}

	@Override
	public List<NewsDuplicateCandidate> findDuplicateCandidates(Instant since, int limit) {
		return jdbcClient.sql("""
			SELECT article.id, article.cluster_id, article.original_title,
			       article.original_excerpt, article.publisher, article.published_at
			FROM news_article article
			WHERE article.collected_at >= :since
			ORDER BY article.published_at DESC
			LIMIT :limit
			""")
			.param("since", atUtc(since))
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new NewsDuplicateCandidate(
				resultSet.getObject("id", UUID.class),
				resultSet.getObject("cluster_id", UUID.class),
				resultSet.getString("original_title"),
				resultSet.getString("original_excerpt"),
				resultSet.getString("publisher"),
				instant(resultSet, "published_at")
			))
			.list();
	}

	@Override
	@Transactional
	public int replaceClusterAssignments(List<NewsClusterAssignment> assignments, Instant reconciledAt) {
		int updated = 0;
		for (NewsClusterAssignment assignment : assignments) {
			updated += jdbcClient.sql("""
				UPDATE news_article
				SET cluster_id = :clusterId
				WHERE id = :articleId AND cluster_id <> :clusterId
				  AND EXISTS (SELECT 1 FROM news_cluster WHERE id = :clusterId)
				""")
				.param("clusterId", assignment.clusterId())
				.param("articleId", assignment.articleId())
				.update();
		}
		jdbcClient.sql("""
			WITH representative AS (
			    SELECT DISTINCT ON (article.cluster_id)
			           article.cluster_id, article.id, article.original_title
			    FROM news_article article
			    ORDER BY article.cluster_id,
			             (article.analysis_status = 'READY') DESC,
			             LENGTH(article.original_excerpt) DESC,
			             article.published_at,
			             article.id
			)
			UPDATE news_cluster cluster
			SET representative_article_id = representative.id,
			    normalized_title = regexp_replace(
			        lower(representative.original_title), '[^0-9a-z가-힣]+', ' ', 'g'
			    ),
			    updated_at = :now
			FROM representative
			WHERE cluster.id = representative.cluster_id
			""")
			.param("now", atUtc(reconciledAt))
			.update();
		jdbcClient.sql("""
			DELETE FROM news_cluster cluster
			WHERE NOT EXISTS (
			    SELECT 1 FROM news_article article WHERE article.cluster_id = cluster.id
			)
			""").update();
		return updated;
	}

	@Override
	public List<NewsStockMapping> findStockMappings() {
		List<NewsStockMapping> stocks = jdbcClient.sql("""
			SELECT s.stock_code, issuer.name_ko, issuer.name_en, s.market
			FROM security s
			JOIN issuer ON issuer.id = s.issuer_id
			JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			ORDER BY s.stock_code
			""")
			.query((resultSet, rowNumber) -> new NewsStockMapping(
				resultSet.getString("stock_code"),
				resultSet.getString("name_ko"),
				resultSet.getString("name_en"),
				resultSet.getString("market"),
				List.of()
			))
			.list();
		return stocks.stream()
			.map(stock -> new NewsStockMapping(
				stock.stockCode(),
				stock.nameKo(),
				stock.nameEn(),
				stock.market(),
				jdbcClient.sql("""
					SELECT stock_alias.alias
					FROM stock_alias
					JOIN security ON security.id = stock_alias.security_id
					WHERE security.stock_code = :stockCode
					ORDER BY alias
					""")
					.param("stockCode", stock.stockCode())
					.query(String.class)
					.list()
			))
			.toList();
	}

	@Override
	@Transactional
	public List<NewsCollectionTarget> findCollectionTargets(int limit) {
		jdbcClient.sql("""
			INSERT INTO news_collection_target (security_id)
			SELECT s.id
			FROM security s
			JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			ON CONFLICT DO NOTHING
			""").update();
		return jdbcClient.sql("""
			SELECT s.stock_code, issuer.name_ko, issuer.name_en
			FROM news_collection_target target
			JOIN security s ON s.id = target.security_id
			JOIN issuer ON issuer.id = s.issuer_id
			ORDER BY target.last_collected_at NULLS FIRST, s.stock_code
			LIMIT :limit
			""")
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new NewsCollectionTarget(
				resultSet.getString("stock_code"),
				resultSet.getString("name_ko"),
				resultSet.getString("name_en")
			))
			.list();
	}

	@Override
	public void markTargetCollected(String stockCode, Instant collectedAt) {
		jdbcClient.sql("""
			UPDATE news_collection_target target
			SET last_collected_at = :collectedAt
			FROM security s
			WHERE target.security_id = s.id
			  AND s.stock_code = :stockCode
			""")
			.param("stockCode", stockCode)
			.param("collectedAt", atUtc(collectedAt))
			.update();
	}

	@Override
	@Transactional
	public boolean saveCollected(NewsDraft draft) {
		UUID clusterId = jdbcClient.sql("""
			SELECT id
			FROM news_cluster
			WHERE id = :clusterId OR signature_hash = :signatureHash
			ORDER BY CASE WHEN id = :clusterId THEN 0 ELSE 1 END
			LIMIT 1
			""")
			.param("clusterId", draft.clusterId())
			.param("signatureHash", draft.signatureHash())
			.query(UUID.class)
			.optional()
			.orElseGet(() -> insertCluster(draft));
		Optional<UUID> inserted = jdbcClient.sql("""
			INSERT INTO news_article (
			    id, cluster_id, provider, provider_article_id, original_title,
			    title_source_hash,
			    original_excerpt, original_url, canonical_url, canonical_url_hash,
			    publisher, thumbnail_url, content_availability, analysis_status,
			    duplicate_score, published_at, collected_at
			)
			VALUES (
			    :id, :clusterId, 'NAVER_NEWS', :providerArticleId, :title,
			    encode(digest(regexp_replace(btrim(:title), '[[:space:]]+', ' ', 'g'), 'sha256'), 'hex'),
			    :excerpt, :originalUrl, :canonicalUrl, :canonicalUrlHash,
			    :publisher, :thumbnailUrl, 'SOURCE_EXCERPT', 'PENDING',
			    :duplicateScore, :publishedAt, :collectedAt
			)
			ON CONFLICT (canonical_url_hash) DO NOTHING
			RETURNING id
			""")
			.param("id", draft.id())
			.param("clusterId", clusterId)
			.param("providerArticleId", draft.providerArticleId())
			.param("title", draft.title())
			.param("excerpt", draft.excerpt())
			.param("originalUrl", draft.originalUrl())
			.param("canonicalUrl", draft.canonicalUrl())
			.param("canonicalUrlHash", draft.canonicalUrlHash())
			.param("duplicateScore", draft.duplicateScore())
			.param("publishedAt", atUtc(draft.publishedAt()))
			.param("collectedAt", atUtc(draft.collectedAt()))
			.param("publisher", draft.publisher(), Types.VARCHAR)
			.param("thumbnailUrl", draft.thumbnailUrl(), Types.VARCHAR)
			.query(UUID.class)
			.optional();
		if (inserted.isEmpty()) {
			return false;
		}
		queueTitleTranslation(draft.title(), draft.collectedAt());
		for (var match : draft.stockConfidences().entrySet()) {
			jdbcClient.sql("""
				INSERT INTO news_article_security (article_id, security_id, match_confidence)
				SELECT :articleId, s.id, :confidence
				FROM security s
				JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
				WHERE s.stock_code = :stockCode
				ON CONFLICT DO NOTHING
				""")
				.param("articleId", draft.id())
				.param("stockCode", match.getKey())
				.param("confidence", match.getValue())
				.update();
		}
		jdbcClient.sql("""
			INSERT INTO news_analysis_job (
			    article_id, status, attempts, next_attempt_at, updated_at
			)
			VALUES (:articleId, 'PENDING', 0, :now, :now)
			""")
			.param("articleId", draft.id())
			.param("now", atUtc(draft.collectedAt()))
			.update();
		jdbcClient.sql("""
			UPDATE news_cluster
			SET representative_article_id = COALESCE(representative_article_id, :articleId),
			    updated_at = :now
			WHERE id = :clusterId
			""")
			.param("articleId", draft.id())
			.param("clusterId", clusterId)
			.param("now", atUtc(draft.collectedAt()))
			.update();
		return true;
	}

	private void queueTitleTranslation(String title, Instant now) {
		jdbcClient.sql("""
			WITH source AS (
			    SELECT regexp_replace(btrim(:title), '[[:space:]]+', ' ', 'g') AS normalized
			), memory AS (
			    INSERT INTO translation_memory (
			        id, content_kind, source_locale, target_locale, translation_version,
			        source_hash, source_text, normalized_source_text, status,
			        created_at, updated_at
			    )
			    SELECT gen_random_uuid(), 'NEWS_TITLE', 'ko', 'en', 'news-title-v1',
			           encode(digest(normalized, 'sha256'), 'hex'), normalized, normalized,
			           'PENDING', :now, :now
			    FROM source
			    ON CONFLICT (content_kind, source_hash, target_locale, translation_version)
			    DO UPDATE SET updated_at = translation_memory.updated_at
			    RETURNING id, status
			)
			INSERT INTO translation_job (
			    translation_memory_id, status, attempts, available_at, created_at, updated_at
			)
			SELECT id, 'PENDING', 0, :now, :now, :now
			FROM memory WHERE status <> 'READY'
			ON CONFLICT (translation_memory_id) DO NOTHING
			""")
			.param("title", title)
			.param("now", atUtc(now))
			.update();
	}

	@Override
	@Transactional
	public List<NewsAnalysisJob> claimAnalysisJobs(int limit, Instant now) {
		Instant staleBefore = now.minus(Duration.ofMinutes(10));
		jdbcClient.sql("""
			UPDATE news_analysis_job
			SET status = CASE WHEN attempts >= 5 THEN 'FAILED' ELSE 'PENDING' END,
			    locked_at = NULL,
			    next_attempt_at = :now,
			    last_error_code = 'STALE_PROCESSING_RECOVERED',
			    updated_at = :now
			WHERE status = 'PROCESSING' AND locked_at < :staleBefore
			""")
			.param("now", atUtc(now))
			.param("staleBefore", atUtc(staleBefore))
			.update();
		List<ClaimedJob> claimed = jdbcClient.sql("""
			WITH selected AS (
			    SELECT article_id
			    FROM news_analysis_job
			    WHERE status = 'PENDING' AND next_attempt_at <= :now
			    ORDER BY next_attempt_at, updated_at, article_id
			    FOR UPDATE SKIP LOCKED
			    LIMIT :limit
			)
			UPDATE news_analysis_job job
			SET status = 'PROCESSING', attempts = attempts + 1,
			    locked_at = :now, updated_at = :now
			FROM selected
			WHERE job.article_id = selected.article_id
			RETURNING job.article_id, job.attempts
			""")
			.param("now", atUtc(now))
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new ClaimedJob(
				resultSet.getObject("article_id", UUID.class),
				resultSet.getInt("attempts")
			))
			.list();
		for (ClaimedJob job : claimed) {
			jdbcClient.sql("UPDATE news_article SET analysis_status = 'PROCESSING' WHERE id = :articleId")
				.param("articleId", job.articleId())
				.update();
		}
		return claimed.stream().map(this::loadJob).toList();
	}

	@Override
	@Transactional
	public void completeAnalysis(UUID articleId, NewsAnalysis analysis, Instant analyzedAt) {
		jdbcClient.sql("""
			UPDATE news_article
			SET event_type = :eventType,
			    sentiment = :sentiment, importance = :importance,
			    market_impact = :marketImpact,
			    market_impact_importance = :marketImpactImportance,
			    market_impact_score = :marketImpactScore,
			    event_confidence = :eventConfidence,
			    sentiment_confidence = :sentimentConfidence,
			    importance_confidence = :importanceConfidence,
			    market_impact_confidence = :marketImpactConfidence,
			    analysis_status = 'READY', model_id = :modelId,
			    prompt_version = :promptVersion, analyzed_at = :analyzedAt
			WHERE id = :articleId
			""")
			.param("articleId", articleId)
			.param("eventType", analysis.eventType())
			.param("sentiment", analysis.sentiment().name())
			.param("importance", analysis.importance().name())
			.param("marketImpact", analysis.marketImpact().name())
			.param("marketImpactImportance", analysis.marketImpactImportance().name())
			.param("marketImpactScore", analysis.marketImpactScore())
			.param("eventConfidence", analysis.eventConfidence())
			.param("sentimentConfidence", analysis.sentimentConfidence())
			.param("importanceConfidence", analysis.importanceConfidence())
			.param("marketImpactConfidence", analysis.marketImpactConfidence())
			.param("modelId", analysis.model())
			.param("promptVersion", analysis.promptVersion())
			.param("analyzedAt", atUtc(analyzedAt))
			.update();
		jdbcClient.sql("""
			UPDATE news_analysis_job
			SET status = 'READY', locked_at = NULL, last_error_code = NULL, updated_at = :now
			WHERE article_id = :articleId
			""")
			.param("articleId", articleId)
			.param("now", atUtc(analyzedAt))
			.update();
	}

	@Override
	@Transactional
	public void failAnalysis(
		UUID articleId,
		int attempts,
		String errorCode,
		Instant now,
		Duration retryDelay
	) {
		String status = attempts >= 5 ? "FAILED" : "PENDING";
		jdbcClient.sql("""
			UPDATE news_analysis_job
			SET status = :status, next_attempt_at = :nextAttemptAt, locked_at = NULL,
			    last_error_code = :errorCode, updated_at = :now
			WHERE article_id = :articleId
			""")
			.param("articleId", articleId)
			.param("status", status)
			.param("nextAttemptAt", atUtc(now.plus(retryDelay)))
			.param("errorCode", errorCode.substring(0, Math.min(100, errorCode.length())))
			.param("now", atUtc(now))
			.update();
		jdbcClient.sql("UPDATE news_article SET analysis_status = :status WHERE id = :articleId")
			.param("articleId", articleId)
			.param("status", status)
			.update();
	}

	@Override
	public List<TermReference> findTermReferences(String selectedText, int limit) {
		return jdbcClient.sql("""
			SELECT id, title_en, definition_en, source_name, source_url
			FROM financial_term_reference
			WHERE normalized_term % :term
			   OR LOWER(:term) LIKE '%' || LOWER(normalized_term) || '%'
			ORDER BY similarity(normalized_term, :term) DESC, normalized_term
			LIMIT :limit
			""")
			.param("term", selectedText.trim())
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new TermReference(
				"G" + resultSet.getObject("id", UUID.class).toString().substring(30),
				resultSet.getString("title_en"),
				resultSet.getString("definition_en"),
				resultSet.getString("source_name"),
				resultSet.getString("source_url")
			))
			.list();
	}

	@Override
	public void recordExplanationClick(
		UUID articleId,
		UUID userId,
		String selectedTextHash,
		String clientIpHash,
		Instant clickedAt
	) {
		JdbcClient.StatementSpec statement = jdbcClient.sql("""
			INSERT INTO financial_term_explanation_click (
			    id, article_id, user_id, selected_text_hash, client_ip_hash, clicked_at
			)
			VALUES (:id, :articleId, :userId, :selectedTextHash, :clientIpHash, :clickedAt)
			""")
			.param("id", UUID.randomUUID())
			.param("articleId", articleId)
			.param("selectedTextHash", selectedTextHash)
			.param("clientIpHash", clientIpHash)
			.param("clickedAt", atUtc(clickedAt));
		statement = nullable(statement, "userId", userId, Types.OTHER);
		statement.update();
	}

	private UUID insertCluster(NewsDraft draft) {
		jdbcClient.sql("""
			INSERT INTO news_cluster (
			    id, signature_hash, normalized_title, created_at, updated_at
			)
			VALUES (:id, :signatureHash, :normalizedTitle, :now, :now)
			ON CONFLICT (signature_hash) DO NOTHING
			""")
			.param("id", draft.clusterId())
			.param("signatureHash", draft.signatureHash())
			.param("normalizedTitle", draft.normalizedTitle())
			.param("now", atUtc(draft.collectedAt()))
			.update();
		return jdbcClient.sql("SELECT id FROM news_cluster WHERE signature_hash = :signatureHash")
			.param("signatureHash", draft.signatureHash())
			.query(UUID.class)
			.single();
	}

	private NewsAnalysisJob loadJob(ClaimedJob claimed) {
		NewsArticle article = findById(claimed.articleId()).orElseThrow();
		List<String> paragraphs = java.util.Arrays.stream(article.sourceText().split("\\R\\s*\\R"))
			.map(String::trim)
			.filter(value -> !value.isBlank())
			.toList();
		List<String> companies = article.relatedStocks().stream()
			.flatMap(stock -> java.util.stream.Stream.of(stock.nameKo(), stock.nameEn()))
			.filter(java.util.Objects::nonNull)
			.toList();
		return new NewsAnalysisJob(
			claimed.articleId(),
			article.originalTitle(),
			paragraphs,
			companies,
			claimed.attempts()
		);
	}

	private NewsArticle withStocks(NewsArticle article) {
		return new NewsArticle(
			article.id(), article.clusterId(), article.originalTitle(), article.originalExcerpt(),
			article.originalBody(), article.englishTitle(), article.englishBody(), article.what(),
			article.why(), article.impact(), article.eventType(), article.sentiment(),
			article.importance(), article.marketImpact(), article.marketImpactImportance(),
			article.marketImpactScore(), article.eventConfidence(),
			article.sentimentConfidence(), article.importanceConfidence(),
			article.marketImpactConfidence(), article.originalUrl(), article.canonicalUrl(),
			article.publisher(), article.thumbnailUrl(), article.contentAvailability(),
			article.analysisStatus(), article.modelId(), article.promptVersion(), article.publishedAt(),
			article.collectedAt(), article.analyzedAt(), article.relatedCoverageCount(),
			findRelatedStocks(article.id())
		);
	}

	private List<RelatedStock> findRelatedStocks(UUID articleId) {
		return jdbcClient.sql("""
			SELECT s.stock_code, issuer.name_ko, issuer.name_en, s.market,
			       MAX(link.match_confidence) AS match_confidence
			FROM news_article source_article
			JOIN news_article related_article
			  ON related_article.cluster_id = source_article.cluster_id
			JOIN news_article_security link ON link.article_id = related_article.id
			JOIN security s ON s.id = link.security_id
			JOIN issuer ON issuer.id = s.issuer_id
			WHERE source_article.id = :articleId
			GROUP BY s.stock_code, issuer.name_ko, issuer.name_en, s.market
			ORDER BY MAX(link.match_confidence) DESC, s.stock_code
			""")
			.param("articleId", articleId)
			.query((resultSet, rowNumber) -> new RelatedStock(
				resultSet.getString("stock_code"),
				resultSet.getString("name_ko"),
				resultSet.getString("name_en"),
				resultSet.getString("market"),
				resultSet.getBigDecimal("match_confidence")
			))
			.list();
	}

	private NewsArticle mapArticle(java.sql.ResultSet resultSet, int rowNumber)
		throws java.sql.SQLException {
		return new NewsArticle(
			resultSet.getObject("id", UUID.class),
			resultSet.getObject("cluster_id", UUID.class),
			resultSet.getString("original_title"),
			resultSet.getString("original_excerpt"),
			resultSet.getString("original_body"),
			resultSet.getString("english_title"),
			resultSet.getString("english_body"),
			resultSet.getString("what_summary"),
			resultSet.getString("why_summary"),
			resultSet.getString("impact_summary"),
			resultSet.getString("event_type"),
			enumValue(NewsSentiment.class, resultSet.getString("sentiment")),
			enumValue(NewsImportance.class, resultSet.getString("importance")),
			enumValue(MarketImpact.class, resultSet.getString("market_impact")),
			enumValue(
				NewsImportance.class,
				resultSet.getString("market_impact_importance")
			),
			resultSet.getBigDecimal("market_impact_score"),
			resultSet.getBigDecimal("event_confidence"),
			resultSet.getBigDecimal("sentiment_confidence"),
			resultSet.getBigDecimal("importance_confidence"),
			resultSet.getBigDecimal("market_impact_confidence"),
			resultSet.getString("original_url"),
			resultSet.getString("canonical_url"),
			resultSet.getString("publisher"),
			resultSet.getString("thumbnail_url"),
			NewsContentAvailability.valueOf(resultSet.getString("content_availability")),
			NewsAnalysisStatus.valueOf(resultSet.getString("analysis_status")),
			resultSet.getString("model_id"),
			resultSet.getString("prompt_version"),
			instant(resultSet, "published_at"),
			instant(resultSet, "collected_at"),
			instant(resultSet, "analyzed_at"),
			resultSet.getLong("related_coverage_count"),
			List.of()
		);
	}

	private String rankExpression(NewsSort sort) {
		return switch (sort) {
			case LATEST -> "0::numeric";
			case IMPORTANCE -> """
				CASE article.importance
				  WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3
				  WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0 END
				""";
			case MARKET_IMPACT -> "COALESCE(article.market_impact_score, 0)";
		};
	}

	private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
		return value == null ? null : Enum.valueOf(type, value);
	}

	private JdbcClient.StatementSpec nullable(
		JdbcClient.StatementSpec statement,
		String name,
		Object value,
		int sqlType
	) {
		return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
	}

	private OffsetDateTime atUtc(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

	private Instant instant(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
		OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private record ClaimedJob(UUID articleId, int attempts) {
	}
}
