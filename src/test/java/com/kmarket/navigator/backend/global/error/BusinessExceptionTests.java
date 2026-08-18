package com.kmarket.navigator.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BusinessExceptionTests {

	@Test
	void keepsPublicErrorCodeAndMessage() {
		BusinessException exception = new BusinessException(ErrorCode.INVALID_REQUEST);

		assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(exception.getMessage()).isEqualTo("The request is invalid.");
	}
}
