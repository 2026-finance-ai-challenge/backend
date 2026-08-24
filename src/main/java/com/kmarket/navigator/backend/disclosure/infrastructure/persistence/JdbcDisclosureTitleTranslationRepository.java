package com.kmarket.navigator.backend.disclosure.infrastructure.persistence;

import static com.kmarket.navigator.backend.disclosure.application.DisclosureTitlePolicy.CONTENT_KIND;
import static com.kmarket.navigator.backend.disclosure.application.DisclosureTitlePolicy.TARGET_LOCALE;
import static com.kmarket.navigator.backend.disclosure.application.DisclosureTitlePolicy.TRANSLATION_VERSION;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureTitleTranslationRepository;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureTitleTranslationJob;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureTitleTranslationSource;

@Repository
class JdbcDisclosureTitleTranslationRepository implements DisclosureTitleTranslationRepository {

	private static final int MAX_ATTEMPTS = 3;
	private final JdbcClient jdbcClient;

	JdbcDisclosureTitleTranslationRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	@Transactional
	public List<DisclosureTitleTranslationJob> claimJobs(
		int limit,
		String workerId,
		Instant now,
		Instant staleBefore
	) {
		jdbcClient.sql("""
			WITH recovered AS (
			    UPDATE translation_job job
			    SET status = CASE WHEN attempts >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
			        locked_at = NULL, locked_by = NULL, available_at = :now,
			        last_error_code = 'STALE_PROCESSING_RECOVERED', updated_at = :now
			    FROM translation_memory memory
			    WHERE memory.id = job.translation_memory_id
			      AND memory.content_kind = :contentKind
			      AND memory.target_locale = :targetLocale
			      AND memory.translation_version = :translationVersion
			      AND job.status = 'PROCESSING' AND job.locked_at < :staleBefore
			    RETURNING memory.id, job.status
			)
			UPDATE translation_memory memory
			SET status = recovered.status, updated_at = :now
			FROM recovered
			WHERE memory.id = recovered.id
			""")
			.param("maxAttempts", MAX_ATTEMPTS)
			.param("now", atUtc(now))
			.param("staleBefore", atUtc(staleBefore))
			.param("contentKind", CONTENT_KIND)
			.param("targetLocale", TARGET_LOCALE)
			.param("translationVersion", TRANSLATION_VERSION)
			.update();

		return jdbcClient.sql("""
			WITH selected AS (
			    SELECT job.translation_memory_id
			    FROM translation_job job
			    JOIN translation_memory memory ON memory.id = job.translation_memory_id
			    WHERE memory.content_kind = :contentKind
			      AND memory.target_locale = :targetLocale
			      AND memory.translation_version = :translationVersion
			      AND job.status = 'PENDING' AND job.available_at <= :now
			    ORDER BY job.available_at, job.updated_at, job.translation_memory_id
			    FOR UPDATE OF job SKIP LOCKED
			    LIMIT :limit
			), claimed AS (
			    UPDATE translation_job job
			    SET status = 'PROCESSING', attempts = attempts + 1,
			        locked_at = :now, locked_by = :workerId, updated_at = :now
			    FROM selected
			    WHERE job.translation_memory_id = selected.translation_memory_id
			    RETURNING job.translation_memory_id, job.attempts
			), marked AS (
			    UPDATE translation_memory memory
			    SET status = 'PROCESSING', updated_at = :now
			    FROM claimed
			    WHERE memory.id = claimed.translation_memory_id
			    RETURNING memory.id, memory.source_hash, memory.normalized_source_text
			)
			SELECT marked.id, marked.source_hash, marked.normalized_source_text, claimed.attempts
			FROM marked
			JOIN claimed ON claimed.translation_memory_id = marked.id
			ORDER BY marked.id
			""")
			.param("contentKind", CONTENT_KIND)
			.param("targetLocale", TARGET_LOCALE)
			.param("translationVersion", TRANSLATION_VERSION)
			.param("now", atUtc(now))
			.param("workerId", workerId)
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new DisclosureTitleTranslationJob(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("source_hash"),
				resultSet.getString("normalized_source_text"),
				resultSet.getInt("attempts")
			))
			.list();
	}

	@Override
	@Transactional
	public void complete(
		UUID translationId,
		String translatedTitle,
		String modelId,
		String promptVersion,
		Instant generatedAt
	) {
		int updated = jdbcClient.sql("""
			UPDATE translation_memory
			SET translated_text = :translatedTitle, status = 'READY',
			    model_id = :modelId, prompt_version = :promptVersion,
			    generated_at = :generatedAt, updated_at = :generatedAt
			WHERE id = :translationId AND status = 'PROCESSING'
			""")
			.param("translatedTitle", translatedTitle)
			.param("modelId", modelId)
			.param("promptVersion", promptVersion)
			.param("generatedAt", atUtc(generatedAt))
			.param("translationId", translationId)
			.update();
		if (updated != 1) {
			throw new IllegalStateException("Claimed title translation no longer exists");
		}
		jdbcClient.sql("""
			UPDATE translation_job
			SET status = 'READY', locked_at = NULL, locked_by = NULL,
			    last_error_code = NULL, updated_at = :generatedAt
			WHERE translation_memory_id = :translationId
			""")
			.param("generatedAt", atUtc(generatedAt))
			.param("translationId", translationId)
			.update();
	}

	@Override
	@Transactional
	public void fail(UUID translationId, String errorCode, Instant failedAt) {
		jdbcClient.sql("""
			UPDATE translation_memory
			SET status = 'FAILED', updated_at = :failedAt
			WHERE id = :translationId AND status = 'PROCESSING'
			""")
			.param("failedAt", atUtc(failedAt))
			.param("translationId", translationId)
			.update();
		jdbcClient.sql("""
			UPDATE translation_job
			SET status = 'FAILED', locked_at = NULL, locked_by = NULL,
			    last_error_code = :errorCode, updated_at = :failedAt
			WHERE translation_memory_id = :translationId
			""")
			.param("errorCode", abbreviate(errorCode, 100))
			.param("failedAt", atUtc(failedAt))
			.param("translationId", translationId)
			.update();
	}

	@Override
	@Transactional
	public void requeueFailed(Instant availableAt) {
		jdbcClient.sql("""
			WITH requeued AS (
			    UPDATE translation_job job
			    SET status = 'PENDING', attempts = 0, available_at = :availableAt,
			        locked_at = NULL, locked_by = NULL, last_error_code = NULL,
			        updated_at = :availableAt
			    FROM translation_memory memory
			    WHERE memory.id = job.translation_memory_id
			      AND memory.content_kind = :contentKind
			      AND memory.target_locale = :targetLocale
			      AND memory.translation_version = :translationVersion
			      AND job.status = 'FAILED'
			    RETURNING memory.id
			)
			UPDATE translation_memory memory
			SET status = 'PENDING', updated_at = :availableAt
			FROM requeued
			WHERE memory.id = requeued.id
			""")
			.param("availableAt", atUtc(availableAt))
			.param("contentKind", CONTENT_KIND)
			.param("targetLocale", TARGET_LOCALE)
			.param("translationVersion", TRANSLATION_VERSION)
			.update();
	}

	@Override
	public List<DisclosureTitleTranslationSource> findOutstandingSources() {
		return jdbcClient.sql("""
			SELECT source_hash, normalized_source_text, status
			FROM translation_memory
			WHERE content_kind = :contentKind
			  AND target_locale = :targetLocale
			  AND translation_version = :translationVersion
			  AND status <> 'READY'
			ORDER BY normalized_source_text, source_hash
			""")
			.param("contentKind", CONTENT_KIND)
			.param("targetLocale", TARGET_LOCALE)
			.param("translationVersion", TRANSLATION_VERSION)
			.query((resultSet, rowNumber) -> new DisclosureTitleTranslationSource(
				resultSet.getString("source_hash"),
				resultSet.getString("normalized_source_text"),
				resultSet.getString("status")
			))
			.list();
	}

	@Override
	public long countSupportedDisclosures() {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM disclosure disclosure
			JOIN security security ON security.id = disclosure.security_id
			JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
			""")
			.query(Long.class)
			.single();
	}

	@Override
	public long countUniqueTitles() {
		return countTitles(null);
	}

	@Override
	public long countReadyTitles() {
		return countTitles("READY");
	}

	private long countTitles(String status) {
		String sql = """
			SELECT count(*)
			FROM translation_memory
			WHERE content_kind = :contentKind
			  AND target_locale = :targetLocale
			  AND translation_version = :translationVersion
			""" + (status == null ? "" : " AND status = :status");
		JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
			.param("contentKind", CONTENT_KIND)
			.param("targetLocale", TARGET_LOCALE)
			.param("translationVersion", TRANSLATION_VERSION);
		if (status != null) {
			statement = statement.param("status", status);
		}
		return statement.query(Long.class).single();
	}

	private static OffsetDateTime atUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static String abbreviate(String value, int maximumLength) {
		return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
	}
}
