package com.kmarket.navigator.backend.news.presentation;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;
import com.kmarket.navigator.backend.identity.infrastructure.ClientContextResolver;
import com.kmarket.navigator.backend.news.application.NewsService;
import com.kmarket.navigator.backend.news.domain.MarketImpact;
import com.kmarket.navigator.backend.news.domain.NewsCursor;
import com.kmarket.navigator.backend.news.domain.NewsImportance;
import com.kmarket.navigator.backend.news.domain.NewsQuery;
import com.kmarket.navigator.backend.news.domain.NewsSentiment;
import com.kmarket.navigator.backend.news.domain.NewsSort;

@Validated
@RestController
@RequestMapping("/api/v1/news")
class NewsController {

	private final NewsService service;
	private final ClientContextResolver clientContextResolver;

	NewsController(NewsService service, ClientContextResolver clientContextResolver) {
		this.service = service;
		this.clientContextResolver = clientContextResolver;
	}

	@GetMapping
	NewsPageResponse findAll(
		@RequestParam(required = false) @Size(max = 120) String query,
		@RequestParam(required = false)
		@Pattern(regexp = "^[0-9A-Z]{6}$")
		String stockCode,
		@RequestParam(required = false) NewsSentiment sentiment,
		@RequestParam(required = false) NewsImportance importance,
		@RequestParam(required = false) MarketImpact marketImpact,
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		Instant from,
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		Instant to,
		@RequestParam(defaultValue = "LATEST") NewsSort sort,
		@RequestParam(required = false) @Size(max = 160) String cursor,
		@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
	) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
		}
		NewsCursor decoded = cursor == null || cursor.isBlank() ? null : NewsCursor.decode(cursor);
		return NewsPageResponse.from(service.findAll(new NewsQuery(
			query == null || query.isBlank() ? null : query.strip(),
			stockCode,
			sentiment,
			importance,
			marketImpact,
			from,
			to,
			sort,
			decoded,
			limit
		)));
	}

	@GetMapping("/{articleId}")
	NewsArticleResponse findOne(@PathVariable UUID articleId) {
		return NewsArticleResponse.from(service.findOne(articleId));
	}

	@PostMapping("/{articleId}/term-explanations")
	TermExplanationResponse explainTerm(
		@PathVariable UUID articleId,
		@Valid @RequestBody TermExplanationRequest body,
		@AuthenticationPrincipal AuthenticatedUser user,
		HttpServletRequest request
	) {
		return TermExplanationResponse.from(service.explainTerm(
			articleId,
			body.selectedText(),
			user == null ? null : user.id(),
			clientContextResolver.resolve(request).ipHash()
		));
	}

	private record TermExplanationRequest(
		@NotBlank @Size(max = 500) String selectedText
	) {
	}
}
