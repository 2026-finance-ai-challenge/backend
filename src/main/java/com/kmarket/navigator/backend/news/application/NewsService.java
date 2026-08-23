package com.kmarket.navigator.backend.news.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.news.application.port.NewsAiGateway;
import com.kmarket.navigator.backend.news.application.port.NewsRepository;
import com.kmarket.navigator.backend.news.domain.NewsArticle;
import com.kmarket.navigator.backend.news.domain.NewsPage;
import com.kmarket.navigator.backend.news.domain.NewsQuery;
import com.kmarket.navigator.backend.news.domain.TermExplanation;
import com.kmarket.navigator.backend.news.domain.TermReference;

@Service
public class NewsService {

	private static final int CONTEXT_RADIUS = 2_000;
	private final NewsRepository repository;
	private final NewsAiGateway aiGateway;
	private final NewsExplanationRateLimiter rateLimiter;
	private final Clock clock;

	@Autowired
	public NewsService(
		NewsRepository repository,
		NewsAiGateway aiGateway,
		NewsExplanationRateLimiter rateLimiter
	) {
		this(repository, aiGateway, rateLimiter, Clock.systemUTC());
	}

	NewsService(
		NewsRepository repository,
		NewsAiGateway aiGateway,
		NewsExplanationRateLimiter rateLimiter,
		Clock clock
	) {
		this.repository = repository;
		this.aiGateway = aiGateway;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
	}

	public NewsPage findAll(NewsQuery query) {
		return repository.findPage(query);
	}

	public NewsArticle findOne(UUID articleId) {
		return repository.findById(articleId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NEWS_NOT_FOUND));
	}

	public TermExplanation explainTerm(
		UUID articleId,
		String selectedText,
		UUID userId,
		String clientIpHash
	) {
		rateLimiter.check(clientIpHash);
		NewsArticle article = findOne(articleId);
		String context = context(article, selectedText);
		List<TermReference> references = new ArrayList<>(
			repository.findTermReferences(selectedText, 5)
		);
		TermReference articleReference = new TermReference(
			"A1",
			"Article context",
			context,
			article.publisher() == null ? "Original article" : article.publisher(),
			article.originalUrl()
		);
		references.addFirst(articleReference);
		repository.recordExplanationClick(
			articleId,
			userId,
			sha256(selectedText),
			clientIpHash,
			Instant.now(clock)
		);
		TermExplanation generated = aiGateway.explainTerm(
			selectedText,
			context,
			references,
			clientIpHash
		);
		Map<String, TermReference> allowed = new LinkedHashMap<>();
		references.forEach(reference -> allowed.put(reference.id(), reference));
		List<TermReference> verifiedSources = generated.sources().stream()
			.map(TermReference::id)
			.distinct()
			.map(allowed::get)
			.filter(java.util.Objects::nonNull)
			.toList();
		boolean evidenceValid = !generated.sufficientEvidence() || !verifiedSources.isEmpty();
		TermExplanation result = evidenceValid
			? new TermExplanation(
				selectedText,
				generated.normalizedTerm(),
				generated.definition(),
				generated.contextualMeaning(),
				verifiedSources,
				generated.confidence(),
				generated.reviewRequired(),
				generated.sufficientEvidence(),
				generated.refusalReason(),
				generated.model(),
				generated.promptVersion()
			)
			: new TermExplanation(
				selectedText,
				null,
				null,
				null,
				List.of(),
				java.math.BigDecimal.ZERO,
				true,
				false,
				"The generated explanation did not contain a verifiable source.",
				generated.model(),
				generated.promptVersion()
			);
		return result;
	}

	private String context(NewsArticle article, String selectedText) {
		String source = String.join("\n\n", article.originalTitle(), article.sourceText());
		int selectedAt = source.indexOf(selectedText);
		if (selectedAt < 0) {
			throw new BusinessException(ErrorCode.INVALID_NEWS_SELECTION);
		}
		int start = Math.max(0, selectedAt - CONTEXT_RADIUS);
		int end = Math.min(source.length(), selectedAt + selectedText.length() + CONTEXT_RADIUS);
		return source.substring(start, end);
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8))
			);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
		}
	}
}
