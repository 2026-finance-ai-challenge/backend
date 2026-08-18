package com.kmarket.navigator.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalExceptionHandlerTests {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void returnsPublicBusinessError() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/disclosures");

		ResponseEntity<ProblemDetail> response = handler.handleBusinessException(
			new BusinessException(ErrorCode.INVALID_REQUEST),
			request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody())
			.isNotNull()
			.extracting(ProblemDetail::getDetail)
			.isEqualTo("The request is invalid.");
	}

	@Test
	void hidesUnexpectedExceptionDetails() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/disclosures");

		ResponseEntity<ProblemDetail> response = handler.handleUnexpectedException(
			new IllegalStateException("database-password"),
			request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody())
			.isNotNull()
			.extracting(ProblemDetail::getDetail)
			.isEqualTo("An unexpected error occurred.");
	}
}
