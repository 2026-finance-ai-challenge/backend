package com.kmarket.navigator.backend.disclosure.presentation;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;

import com.kmarket.navigator.backend.disclosure.application.DisclosureQueryHandler;
import com.kmarket.navigator.backend.disclosure.application.DisclosureInsightHandler;
import com.kmarket.navigator.backend.disclosure.application.DisclosureQuestionHandler;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureCursor;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.infrastructure.ClientContextResolver;
import com.kmarket.navigator.backend.translation.application.OnDemandTranslationService;
import com.kmarket.navigator.backend.translation.domain.TranslationStatus;
import com.kmarket.navigator.backend.translation.presentation.TranslationResponse;

import tools.jackson.databind.ObjectMapper;

@Validated
@RestController
@RequestMapping("/api/v1/disclosures")
class DisclosureController {

	private final DisclosureQueryHandler queryHandler;
	private final DisclosureQuestionHandler questionHandler;
	private final DisclosureInsightHandler insightHandler;
	private final OnDemandTranslationService translationService;
	private final ClientContextResolver clientContextResolver;
	private final ObjectMapper objectMapper;

	DisclosureController(
		DisclosureQueryHandler queryHandler,
		DisclosureQuestionHandler questionHandler,
		DisclosureInsightHandler insightHandler,
		OnDemandTranslationService translationService,
		ClientContextResolver clientContextResolver,
		ObjectMapper objectMapper
	) {
		this.queryHandler = queryHandler;
		this.questionHandler = questionHandler;
		this.insightHandler = insightHandler;
		this.translationService = translationService;
		this.clientContextResolver = clientContextResolver;
		this.objectMapper = objectMapper;
	}

	@GetMapping("/{receiptNumber}/insight")
	DisclosureInsightResponse findInsight(
		@PathVariable
		@Pattern(regexp = "^[0-9]{14}$")
		String receiptNumber
	) {
		return DisclosureInsightResponse.from(insightHandler.find(receiptNumber));
	}

	@PostMapping("/{receiptNumber}/insight")
	DisclosureInsightResponse generateInsight(
		@PathVariable
		@Pattern(regexp = "^[0-9]{14}$")
		String receiptNumber
	) {
		return DisclosureInsightResponse.from(insightHandler.generate(receiptNumber));
	}

	@GetMapping
	DisclosurePageResponse findAll(
		@RequestParam(required = false) @Size(max = 120) String query,
		@RequestParam(required = false)
		@Pattern(regexp = "^[0-9A-Z]{6}$")
		String stockCode,
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		LocalDate from,
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		LocalDate to,
		@RequestParam(required = false)
		List<DisclosureType> types,
		@RequestParam(required = false)
		Boolean correction,
		@RequestParam(required = false)
		@Size(max = 100)
		String cursor,
		@RequestParam(defaultValue = "20")
		@Min(1) @Max(50)
		int limit
	) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
		}
		DisclosureCursor decodedCursor = cursor == null || cursor.isBlank()
			? null
			: DisclosureCursor.decode(cursor);
		var listQuery = new DisclosureListQuery(
			query == null || query.isBlank() ? null : query.strip(),
			stockCode,
			from,
			to,
			types == null ? null : Set.copyOf(types),
			correction,
			decodedCursor,
			limit
		);
		return DisclosurePageResponse.from(queryHandler.findAll(listQuery));
	}

	@GetMapping("/{receiptNumber}")
	DisclosureDetailResponse findOne(
		@PathVariable
		@Pattern(regexp = "^[0-9]{14}$")
		String receiptNumber
	) {
		return DisclosureDetailResponse.from(queryHandler.findPublished(receiptNumber), objectMapper);
	}

	@GetMapping("/{receiptNumber}/sections/{sectionId}/translation")
	TranslationResponse findSectionTranslation(
		@PathVariable @Pattern(regexp = "^[0-9]{14}$") String receiptNumber,
		@PathVariable UUID sectionId
	) {
		return TranslationResponse.from(
			translationService.findDisclosureSection(receiptNumber, sectionId)
		);
	}

	@PostMapping("/{receiptNumber}/sections/{sectionId}/translation")
	ResponseEntity<TranslationResponse> requestSectionTranslation(
		@PathVariable @Pattern(regexp = "^[0-9]{14}$") String receiptNumber,
		@PathVariable UUID sectionId,
		HttpServletRequest request
	) {
		var result = translationService.requestDisclosureSection(
			receiptNumber,
			sectionId,
			clientContextResolver.resolve(request).ipHash()
		);
		var builder = result.status() == TranslationStatus.READY
			? ResponseEntity.ok()
			: ResponseEntity.accepted().header(HttpHeaders.RETRY_AFTER, "2");
		return builder.body(TranslationResponse.from(result));
	}

	@PostMapping("/{receiptNumber}/questions")
	DisclosureAnswerResponse ask(
		@PathVariable
		@Pattern(regexp = "^[0-9]{14}$")
		String receiptNumber,
		@Valid @RequestBody DisclosureQuestionRequest request
	) {
		return DisclosureAnswerResponse.from(
			questionHandler.ask(receiptNumber, request.toDomain())
		);
	}

	@PostMapping("/{receiptNumber}/index")
	ResponseEntity<Void> requestIndexing(
		@PathVariable
		@Pattern(regexp = "^[0-9]{14}$")
		String receiptNumber
	) {
		queryHandler.requestIndexing(receiptNumber);
		return ResponseEntity.accepted().build();
	}
}
