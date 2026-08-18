package com.kmarket.navigator.backend.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
	INVALID_CURSOR(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "The pagination cursor is invalid."),
	INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "The date range is invalid."),
	DISCLOSURE_NOT_FOUND(HttpStatus.NOT_FOUND, "DISCLOSURE_NOT_FOUND", "The disclosure was not found."),
	DISCLOSURE_INDEX_NOT_READY(
		HttpStatus.CONFLICT,
		"DISCLOSURE_INDEX_NOT_READY",
		"The disclosure is not ready for questions."
	),
	AI_SERVICE_UNAVAILABLE(
		HttpStatus.SERVICE_UNAVAILABLE,
		"AI_SERVICE_UNAVAILABLE",
		"The AI service is temporarily unavailable."
	),
	INTERNAL_SERVER_ERROR(
		HttpStatus.INTERNAL_SERVER_ERROR,
		"INTERNAL_SERVER_ERROR",
		"An unexpected error occurred."
	);

	private final HttpStatus status;
	private final String code;
	private final String message;

	ErrorCode(HttpStatus status, String code, String message) {
		this.status = status;
		this.code = code;
		this.message = message;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public String message() {
		return message;
	}
}
