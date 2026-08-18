package com.kmarket.navigator.backend.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
	INVALID_CURSOR(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "The pagination cursor is invalid."),
	INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "The date range is invalid."),
	DISCLOSURE_NOT_FOUND(HttpStatus.NOT_FOUND, "DISCLOSURE_NOT_FOUND", "The disclosure was not found."),
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
