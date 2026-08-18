package com.kmarket.navigator.backend.disclosure.presentation;

import java.time.LocalDate;
import java.util.Set;

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

import com.kmarket.navigator.backend.disclosure.application.DisclosureQueryHandler;
import com.kmarket.navigator.backend.disclosure.application.DisclosureQuestionHandler;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureCursor;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

import tools.jackson.databind.ObjectMapper;

@Validated
@RestController
@RequestMapping("/api/v1/disclosures")
class DisclosureController {

	private final DisclosureQueryHandler queryHandler;
	private final DisclosureQuestionHandler questionHandler;
	private final ObjectMapper objectMapper;

	DisclosureController(
		DisclosureQueryHandler queryHandler,
		DisclosureQuestionHandler questionHandler,
		ObjectMapper objectMapper
	) {
		this.queryHandler = queryHandler;
		this.questionHandler = questionHandler;
		this.objectMapper = objectMapper;
	}

	@GetMapping
	DisclosurePageResponse findAll(
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
		Set<DisclosureType> types,
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
		var query = new DisclosureListQuery(stockCode, from, to, types, decodedCursor, limit);
		return DisclosurePageResponse.from(queryHandler.findAll(query));
	}

	@GetMapping("/{receiptNumber}")
	DisclosureDetailResponse findOne(
		@PathVariable
		@Pattern(regexp = "^[0-9]{14}$")
		String receiptNumber
	) {
		return DisclosureDetailResponse.from(queryHandler.findOne(receiptNumber), objectMapper);
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
}
