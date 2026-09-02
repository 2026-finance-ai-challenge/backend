package com.kmarket.navigator.backend.translation.infrastructure;

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

import com.kmarket.navigator.backend.global.text.EnglishTextPolicy;
import com.kmarket.navigator.backend.translation.application.port.TranslationRepository;
import com.kmarket.navigator.backend.translation.domain.GeneratedTranslation;
import com.kmarket.navigator.backend.translation.domain.GeneratedTitle;
import com.kmarket.navigator.backend.translation.domain.TranslationJob;
import com.kmarket.navigator.backend.translation.domain.TranslationKind;
import com.kmarket.navigator.backend.translation.domain.TranslationStatus;
import com.kmarket.navigator.backend.translation.domain.TranslationView;
import com.kmarket.navigator.backend.translation.domain.TitleTranslationJob;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
class JdbcTranslationRepository implements TranslationRepository {

	private static final int MAX_ATTEMPTS = 1;
	private static final String NEWS_TITLE_VERSION = "news-title-v3";
	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	JdbcTranslationRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<TranslationView> find(
		TranslationKind kind,
		String sourceHash,
		String targetLocale,
		String version
	) {
		return jdbcClient.sql("""
			SELECT memory.id, memory.source_hash, memory.target_locale,
			       memory.translation_version, memory.status, memory.result_payload,
			       memory.model_id, memory.prompt_version, memory.generated_at,
			       job.last_error_code
			FROM translation_memory memory
			LEFT JOIN translation_job job ON job.translation_memory_id = memory.id
			WHERE memory.content_kind = :kind AND memory.source_hash = :sourceHash
			  AND memory.target_locale = :targetLocale AND memory.translation_version = :version
			""")
			.param("kind", kind.name())
			.param("sourceHash", sourceHash)
			.param("targetLocale", targetLocale)
			.param("version", version)
			.query(this::mapView)
			.optional();
	}

	@Override
	public java.util.Map<String, TranslationView> findMany(TranslationKind kind, List<String> sourceHashes, String targetLocale, String version) {
		var results = new java.util.HashMap<String, TranslationView>();
		var hashes = sourceHashes.stream().distinct().toList();
		// 섹션마다 조회하지 않고 문서 캐시를 제한된 묶음으로 읽는다.
		for (int start = 0; start < hashes.size(); start += 500) {
			jdbcClient.sql("""
				SELECT memory.id, memory.source_hash, memory.target_locale, memory.translation_version,
				    memory.status, memory.result_payload, memory.model_id, memory.prompt_version,
				    memory.generated_at, job.last_error_code
				FROM translation_memory memory
				LEFT JOIN translation_job job ON job.translation_memory_id = memory.id
				WHERE memory.content_kind = :kind AND memory.source_hash IN (:hashes)
				    AND memory.target_locale = :locale AND memory.translation_version = :version
				""").param("kind", kind.name()).param("hashes", hashes.subList(start, Math.min(start + 500, hashes.size())))
				.param("locale", targetLocale).param("version", version).query(this::mapView).list()
				.forEach(view -> results.put(view.sourceHash(), view));
		}
		return java.util.Map.copyOf(results);
	}

	@Override
	@Transactional
	public TranslationView request(
		TranslationKind kind,
		String sourceHash,
		String canonicalSource,
		JsonNode context,
		String targetLocale,
		String version,
		Instant now
	) {
		UUID id = UUID.randomUUID();
		jdbcClient.sql("""
			INSERT INTO translation_memory (
			    id, content_kind, source_locale, target_locale, translation_version,
			    source_hash, source_text, normalized_source_text, status,
			    request_context, created_at, updated_at
			)
			VALUES (
			    :id, :kind, 'ko', :targetLocale, :version, :sourceHash, :source,
			    :source, 'PENDING', CAST(:context AS jsonb), :now, :now
			)
			ON CONFLICT (content_kind, source_hash, target_locale, translation_version)
			DO NOTHING
			""")
			.param("id", id)
			.param("kind", kind.name())
			.param("version", version)
			.param("targetLocale", targetLocale)
			.param("sourceHash", sourceHash)
			.param("source", canonicalSource)
			.param("context", objectMapper.writeValueAsString(context))
			.param("now", atUtc(now))
			.update();
		// 실패 작업 재등록도 같은 메모리 행을 잠가 동시 요청이 상태를 되돌리지 못하게 한다.
		jdbcClient.sql("""
			SELECT id FROM translation_memory
			WHERE content_kind = :kind AND source_hash = :hash AND target_locale = :locale
			  AND translation_version = :version FOR UPDATE
			""").param("kind", kind.name()).param("hash", sourceHash).param("locale", targetLocale)
			.param("version", version).query(UUID.class).single();
		TranslationView current = find(kind, sourceHash, targetLocale, version).orElseThrow();
		if (current.status() == TranslationStatus.FAILED) {
			boolean coolingDown = jdbcClient.sql("""
				SELECT EXISTS (SELECT 1 FROM translation_job WHERE translation_memory_id = :id
				  AND (updated_at > :before OR available_at > :now))
				""").param("id", current.jobId()).param("before", atUtc(now.minus(Duration.ofMinutes(15))))
				.param("now", atUtc(now)).query(Boolean.class).single();
			if (coolingDown) return current;
			jdbcClient.sql("""
				UPDATE translation_memory
				SET status = 'PENDING', result_payload = NULL, model_id = NULL,
				    prompt_version = NULL, generated_at = NULL, updated_at = :now
				WHERE id = :id AND status = 'FAILED'
				""")
				.param("id", current.jobId())
				.param("now", atUtc(now))
				.update();
		}
		jdbcClient.sql("""
			INSERT INTO translation_job (
			    translation_memory_id, status, attempts, available_at, created_at, updated_at
			)
			VALUES (:id, 'PENDING', 0, :now, :now, :now)
			ON CONFLICT (translation_memory_id) DO UPDATE
			SET status = CASE
			        WHEN translation_job.status = 'FAILED' THEN 'PENDING'
			        ELSE translation_job.status
			    END,
			    attempts = CASE WHEN translation_job.status = 'FAILED' THEN 0 ELSE translation_job.attempts END,
			    available_at = CASE WHEN translation_job.status = 'FAILED' THEN EXCLUDED.available_at ELSE translation_job.available_at END,
			    locked_at = CASE WHEN translation_job.status = 'FAILED' THEN NULL ELSE translation_job.locked_at END,
			    locked_by = CASE WHEN translation_job.status = 'FAILED' THEN NULL ELSE translation_job.locked_by END,
			    last_error_code = CASE WHEN translation_job.status = 'FAILED' THEN NULL ELSE translation_job.last_error_code END,
			    updated_at = CASE WHEN translation_job.status = 'FAILED' THEN EXCLUDED.updated_at ELSE translation_job.updated_at END
			""")
			.param("id", current.jobId())
			.param("now", atUtc(now))
			.update();
		return find(kind, sourceHash, targetLocale, version).orElseThrow();
	}

	@Override
	public void prioritize(UUID id, Instant now) {
		jdbcClient.sql("""
			UPDATE translation_job
			SET priority = 0, available_at = LEAST(available_at, :now), updated_at = :now
			WHERE translation_memory_id = :id AND status = 'PENDING'
			""")
			.param("id", id)
			.param("now", atUtc(now))
			.update();
	}

	@Override
	@Transactional
	public List<TranslationJob> claim(int limit, String workerId, Instant now, Instant staleBefore) {
		markExhausted("NEWS_NARRATIVE", now);
		markExhausted("DISCLOSURE_SECTION", now);
		jdbcClient.sql("""
			WITH recovered AS (
			    UPDATE translation_job job
			    SET status = CASE WHEN attempts >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
			        locked_at = NULL, locked_by = NULL, available_at = :now,
			        last_error_code = 'STALE_PROCESSING_RECOVERED', updated_at = :now
			    FROM translation_memory memory
			    WHERE memory.id = job.translation_memory_id
			      AND memory.content_kind IN ('NEWS_NARRATIVE', 'DISCLOSURE_SECTION')
			      AND job.status = 'PROCESSING' AND job.locked_at < :staleBefore
			    RETURNING memory.id, job.status
			)
			UPDATE translation_memory memory
			SET status = recovered.status, updated_at = :now
			FROM recovered WHERE memory.id = recovered.id
			""")
			.param("maxAttempts", MAX_ATTEMPTS)
			.param("now", atUtc(now))
			.param("staleBefore", atUtc(staleBefore))
			.update();
		return jdbcClient.sql("""
			WITH selected AS (
			    SELECT job.translation_memory_id
			    FROM translation_job job
			    JOIN translation_memory memory ON memory.id = job.translation_memory_id
			    WHERE memory.content_kind IN ('NEWS_NARRATIVE', 'DISCLOSURE_SECTION')
			      AND job.status = 'PENDING' AND job.attempts < :maxAttempts
			      AND job.available_at <= :now
			    ORDER BY job.priority, job.available_at, job.updated_at, job.translation_memory_id
			    FOR UPDATE OF job SKIP LOCKED LIMIT :limit
			), claimed AS (
			    UPDATE translation_job job
			    SET status = 'PROCESSING', attempts = attempts + 1,
			        locked_at = :now, locked_by = :workerId, updated_at = :now
			    FROM selected WHERE job.translation_memory_id = selected.translation_memory_id
			    RETURNING job.translation_memory_id, job.attempts,
			              job.priority, job.available_at
			), marked AS (
			    UPDATE translation_memory memory
			    SET status = 'PROCESSING', updated_at = :now
			    FROM claimed WHERE memory.id = claimed.translation_memory_id
			    RETURNING memory.id, memory.content_kind, memory.source_hash,
			              memory.source_text, memory.request_context,
			              memory.target_locale, memory.translation_version
			)
			SELECT marked.*, claimed.attempts
			FROM marked JOIN claimed ON claimed.translation_memory_id = marked.id
			ORDER BY claimed.priority, claimed.available_at, marked.id
			""")
			.param("now", atUtc(now))
			.param("workerId", workerId)
			.param("limit", limit)
			.param("maxAttempts", MAX_ATTEMPTS)
			.query((resultSet, rowNumber) -> new TranslationJob(
				resultSet.getObject("id", UUID.class),
				TranslationKind.valueOf(resultSet.getString("content_kind")),
				resultSet.getString("source_hash"),
				resultSet.getString("source_text"),
				objectMapper.readTree(resultSet.getString("request_context")),
				resultSet.getString("target_locale"),
				resultSet.getString("translation_version"),
				resultSet.getInt("attempts")
			))
			.list();
	}

	@Override
	@Transactional
	public List<TitleTranslationJob> claimNewsTitles(
		int limit,
		String workerId,
		Instant now,
		Instant staleBefore
	) {
		recover("NEWS_TITLE", now, staleBefore);
		return jdbcClient.sql("""
			WITH selected AS (
			    SELECT job.translation_memory_id
			    FROM translation_job job
			    JOIN translation_memory memory ON memory.id = job.translation_memory_id
			    WHERE memory.content_kind = 'NEWS_TITLE'
			      AND memory.target_locale = 'en'
			      AND memory.translation_version = :translationVersion
			      AND job.status = 'PENDING' AND job.attempts < :maxAttempts
			      AND job.available_at <= :now
			    ORDER BY job.priority, memory.created_at DESC, job.available_at DESC,
			             job.translation_memory_id
			    FOR UPDATE OF job SKIP LOCKED LIMIT :limit
			), claimed AS (
			    UPDATE translation_job job
			    SET status = 'PROCESSING', attempts = attempts + 1,
			        locked_at = :now, locked_by = :workerId, updated_at = :now
			    FROM selected WHERE job.translation_memory_id = selected.translation_memory_id
			    RETURNING job.translation_memory_id, job.attempts
			), marked AS (
			    UPDATE translation_memory memory
			    SET status = 'PROCESSING', updated_at = :now
			    FROM claimed WHERE memory.id = claimed.translation_memory_id
			    RETURNING memory.id, memory.source_hash, memory.source_text,
			              memory.translation_version
			)
			SELECT marked.*, claimed.attempts
			FROM marked JOIN claimed ON claimed.translation_memory_id = marked.id
			ORDER BY marked.id
			""")
			.param("now", atUtc(now))
			.param("workerId", workerId)
			.param("limit", limit)
			.param("maxAttempts", MAX_ATTEMPTS)
			.param("translationVersion", NEWS_TITLE_VERSION)
			.query((resultSet, rowNumber) -> new TitleTranslationJob(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("source_hash"),
				resultSet.getString("source_text"),
				resultSet.getString("translation_version"),
				resultSet.getInt("attempts")
			))
			.list();
	}

	@Override
	@Transactional
	public void complete(UUID id, GeneratedTranslation generated, Instant now) {
		TranslationKind kind = jdbcClient.sql("""
			SELECT content_kind FROM translation_memory WHERE id = :id
			""")
			.param("id", id)
			.query(String.class)
			.optional()
			.map(TranslationKind::valueOf)
			.orElseThrow(() -> new IllegalStateException("Claimed translation no longer exists"));
		if ("en".equals(generated.targetLocale())) {
			validateGenerated(generated);
		}
		int updated = jdbcClient.sql("""
			UPDATE translation_memory
			SET result_payload = CAST(:result AS jsonb), status = 'READY',
			    model_id = :modelId, prompt_version = :promptVersion,
			    generated_at = :now, updated_at = :now
			WHERE id = :id AND status = 'PROCESSING' AND source_hash = :sourceHash
			  AND target_locale = :targetLocale AND translation_version = :version
			""")
			.param("id", id)
			.param("result", objectMapper.writeValueAsString(generated.result()))
			.param("modelId", generated.modelId())
			.param("promptVersion", generated.promptVersion())
			.param("sourceHash", generated.sourceHash())
			.param("targetLocale", generated.targetLocale())
			.param("version", generated.translationVersion())
			.param("now", atUtc(now))
			.update();
		if (updated != 1) {
			throw new IllegalStateException("Claimed translation changed before completion");
		}
		if (kind == TranslationKind.NEWS_NARRATIVE) {
			copyNewsNarrative(id, generated);
		}
		jdbcClient.sql("""
			UPDATE translation_job
			SET status = 'READY', locked_at = NULL, locked_by = NULL,
			    last_error_code = NULL, updated_at = :now
			WHERE translation_memory_id = :id
			""")
			.param("id", id)
			.param("now", atUtc(now))
			.update();
	}

	private void copyNewsNarrative(UUID id, GeneratedTranslation generated) {
		JsonNode result = generated.result();
		JsonNode paragraphNodes = result.path("translatedParagraphs");
		if (!paragraphNodes.isArray() || paragraphNodes.isEmpty()) {
			throw new IllegalArgumentException("News translation paragraphs must not be empty");
		}
		List<String> paragraphs = new ArrayList<>();
		paragraphNodes.forEach(node -> paragraphs.add(node.stringValue()));
		int updated = "en".equals(generated.targetLocale())
			? copyEnglishNewsNarrative(id, result, paragraphs)
			: copyKoreanNewsNarrative(id, result);
		if (updated != 1) {
			throw new IllegalStateException("News narrative target no longer exists");
		}
		if (result.path("summaries").has("ko")) {
			copyKoreanNewsNarrative(id, result.path("summaries").path("ko"));
		}
	}

	@Override
	@Transactional
	public void progress(UUID id, GeneratedTranslation generated, Instant now) {
		validateGenerated(generated);
		int updated = jdbcClient.sql("""
			UPDATE translation_memory SET result_payload = CAST(:payload AS jsonb),
			    model_id = :model, prompt_version = :prompt, updated_at = :now
			WHERE id = :id AND status = 'PROCESSING' AND source_hash = :hash
			  AND target_locale = :locale AND translation_version = :version
			""")
			.param("id", id).param("payload", objectMapper.writeValueAsString(generated.result()))
			.param("model", generated.modelId()).param("prompt", generated.promptVersion())
			.param("now", atUtc(now)).param("hash", generated.sourceHash())
			.param("locale", generated.targetLocale()).param("version", generated.translationVersion())
			.update();
		if (updated != 1) throw new IllegalStateException("Translation progress lease expired");
	}

	private void validateGenerated(GeneratedTranslation generated) {
		var result = generated.result();
		if (result.has("summaries")) {
			var english = ((tools.jackson.databind.node.ObjectNode) result).deepCopy();
			english.remove("summaries");
			EnglishTextPolicy.requireAllTextValid(english);
			EnglishTextPolicy.requireAllTextValid(result.path("summaries").path("en"));
			for (String key : List.of("what", "why", "impact")) {
				requireKoreanSummary(result.path("summaries").path("ko").path(key).asString());
			}
		} else EnglishTextPolicy.requireAllTextValid(result);
	}

	private int copyEnglishNewsNarrative(UUID id, JsonNode result, List<String> paragraphs) {
		paragraphs.replaceAll(EnglishTextPolicy::requireValid);
		return jdbcClient.sql("""
			UPDATE news_article article
			SET english_body = :englishBody,
			    what_summary = :whatSummary,
			    why_summary = :whySummary,
			    impact_summary = :impactSummary
			FROM translation_memory memory
			WHERE memory.id = :id
			  AND memory.content_kind = 'NEWS_NARRATIVE'
			  AND article.id = CAST(memory.request_context ->> 'article_id' AS uuid)
			""")
			.param("id", id)
			.param("englishBody", String.join("\n\n", paragraphs))
			.param("whatSummary", EnglishTextPolicy.requireValid(result.path("what").stringValue()))
			.param("whySummary", EnglishTextPolicy.requireValid(result.path("why").stringValue()))
			.param("impactSummary", EnglishTextPolicy.requireValid(result.path("impact").stringValue()))
			.update();
	}

	private int copyKoreanNewsNarrative(UUID id, JsonNode result) {
		return jdbcClient.sql("""
			UPDATE news_article article
			SET what_summary_ko = :whatSummary,
			    why_summary_ko = :whySummary,
			    impact_summary_ko = :impactSummary
			FROM translation_memory memory
			WHERE memory.id = :id
			  AND memory.content_kind = 'NEWS_NARRATIVE'
			  AND (memory.target_locale = 'ko' OR jsonb_exists(memory.result_payload, 'summaries'))
			  AND article.id = CAST(memory.request_context ->> 'article_id' AS uuid)
			""")
			.param("id", id)
			.param("whatSummary", requireKoreanSummary(result.path("what").stringValue()))
			.param("whySummary", requireKoreanSummary(result.path("why").stringValue()))
			.param("impactSummary", requireKoreanSummary(result.path("impact").stringValue()))
			.update();
	}

	private String requireKoreanSummary(String value) {
		if (value == null || value.isBlank() || !value.matches(".*[가-힣].*")) {
			throw new IllegalArgumentException("Korean summary must be non-blank and contain Hangul");
		}
		return value;
	}

	@Override
	@Transactional
	public void completeNewsTitle(GeneratedTitle generated, Instant now) {
		int updated = jdbcClient.sql("""
			UPDATE translation_memory
			SET translated_text = :translatedText, status = 'READY',
			    model_id = :modelId, prompt_version = :promptVersion,
			    generated_at = :now, updated_at = :now
			WHERE id = :id AND status = 'PROCESSING' AND content_kind = 'NEWS_TITLE'
			  AND source_hash = :sourceHash AND target_locale = :targetLocale
			  AND translation_version = :version
			""")
			.param("id", generated.id())
			.param("translatedText", generated.translatedText())
			.param("modelId", generated.modelId())
			.param("promptVersion", generated.promptVersion())
			.param("sourceHash", generated.sourceHash())
			.param("targetLocale", generated.targetLocale())
			.param("version", generated.translationVersion())
			.param("now", atUtc(now))
			.update();
		if (updated != 1) {
			throw new IllegalStateException("Claimed news title changed before completion");
		}
		jdbcClient.sql("""
			UPDATE news_article
			SET english_title = :translatedText
			WHERE title_source_hash = :sourceHash
			""")
			.param("translatedText", generated.translatedText())
			.param("sourceHash", generated.sourceHash())
			.update();
		markReady(generated.id(), now);
	}

	@Override
	@Transactional
	public void fail(UUID id, int attempts, String errorCode, Instant now, Duration delay) {
		String status = attempts >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
		jdbcClient.sql("""
			WITH failed AS (
			    UPDATE translation_job
			    SET status = :status, available_at = :availableAt,
			        locked_at = NULL, locked_by = NULL, last_error_code = :errorCode,
			        updated_at = :now
			    WHERE translation_memory_id = :id AND status = 'PROCESSING' AND attempts = :attempts
			    RETURNING translation_memory_id
			)
			UPDATE translation_memory memory SET status = :status, updated_at = :now
			FROM failed WHERE memory.id = failed.translation_memory_id AND memory.status = 'PROCESSING'
			""")
			.param("id", id)
			.param("attempts", attempts)
			.param("status", status)
			.param("availableAt", atUtc(now.plus(delay)))
			.param("errorCode", abbreviate(errorCode))
			.param("now", atUtc(now))
			.update();
	}

	private TranslationView mapView(java.sql.ResultSet resultSet, int rowNumber)
		throws java.sql.SQLException {
		String payload = resultSet.getString("result_payload");
		OffsetDateTime generatedAt = resultSet.getObject("generated_at", OffsetDateTime.class);
		return new TranslationView(
			resultSet.getObject("id", UUID.class),
			resultSet.getString("source_hash"),
			resultSet.getString("target_locale"),
			resultSet.getString("translation_version"),
			TranslationStatus.valueOf(resultSet.getString("status")),
			payload == null ? null : objectMapper.readTree(payload),
			resultSet.getString("model_id"),
			resultSet.getString("prompt_version"),
			generatedAt == null ? null : generatedAt.toInstant(),
			resultSet.getString("last_error_code")
		);
	}

	private void recover(String kind, Instant now, Instant staleBefore) {
		jdbcClient.sql("""
			WITH recovered AS (
			    UPDATE translation_job job
			    SET status = CASE WHEN attempts >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
			        locked_at = NULL, locked_by = NULL, available_at = :now,
			        last_error_code = 'STALE_PROCESSING_RECOVERED', updated_at = :now
			    FROM translation_memory memory
			    WHERE memory.id = job.translation_memory_id
			      AND memory.content_kind = :kind
			      AND job.status = 'PROCESSING' AND job.locked_at < :staleBefore
			    RETURNING memory.id, job.status
			)
			UPDATE translation_memory memory
			SET status = recovered.status, updated_at = :now
			FROM recovered WHERE memory.id = recovered.id
			""")
			.param("maxAttempts", MAX_ATTEMPTS)
			.param("kind", kind)
			.param("now", atUtc(now))
			.param("staleBefore", atUtc(staleBefore))
			.update();
		markExhausted(kind, now);
	}

	private void markExhausted(String kind, Instant now) {
		jdbcClient.sql("""
			WITH exhausted AS (
			    UPDATE translation_job job
			    SET status = 'FAILED', locked_at = NULL, locked_by = NULL,
			        last_error_code = COALESCE(job.last_error_code, 'AUTOMATIC_RETRY_LIMIT_REACHED'),
			        updated_at = :now
			    FROM translation_memory memory
			    WHERE memory.id = job.translation_memory_id
			      AND memory.content_kind = :kind
			      AND job.status = 'PENDING' AND job.attempts >= :maxAttempts
			    RETURNING memory.id
			)
			UPDATE translation_memory memory
			SET status = 'FAILED', updated_at = :now
			FROM exhausted WHERE memory.id = exhausted.id
			""")
			.param("kind", kind)
			.param("maxAttempts", MAX_ATTEMPTS)
			.param("now", atUtc(now))
			.update();
	}

	private void markReady(UUID id, Instant now) {
		jdbcClient.sql("""
			UPDATE translation_job
			SET status = 'READY', locked_at = NULL, locked_by = NULL,
			    last_error_code = NULL, updated_at = :now
			WHERE translation_memory_id = :id
			""")
			.param("id", id)
			.param("now", atUtc(now))
			.update();
	}

	private static OffsetDateTime atUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static String abbreviate(String value) {
		return value.length() <= 100 ? value : value.substring(0, 100);
	}
}
