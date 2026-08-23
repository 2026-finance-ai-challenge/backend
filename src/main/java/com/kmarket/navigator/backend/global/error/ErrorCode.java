package com.kmarket.navigator.backend.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
	INVALID_CURSOR(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "The pagination cursor is invalid."),
	INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "The date range is invalid."),
	AUTHENTICATION_REQUIRED(
		HttpStatus.UNAUTHORIZED,
		"AUTHENTICATION_REQUIRED",
		"Authentication is required."
	),
	INVALID_LOGIN_CREDENTIALS(
		HttpStatus.UNAUTHORIZED,
		"INVALID_LOGIN_CREDENTIALS",
		"The login ID or password is invalid."
	),
	INVALID_REFRESH_TOKEN(
		HttpStatus.UNAUTHORIZED,
		"INVALID_REFRESH_TOKEN",
		"The refresh token is invalid or expired."
	),
	REFRESH_TOKEN_REUSE_DETECTED(
		HttpStatus.UNAUTHORIZED,
		"REFRESH_TOKEN_REUSE_DETECTED",
		"Refresh token reuse was detected. Sign in again."
	),
	LOGIN_RATE_LIMITED(
		HttpStatus.TOO_MANY_REQUESTS,
		"LOGIN_RATE_LIMITED",
		"Too many login attempts. Try again later."
	),
	LOGIN_ID_ALREADY_EXISTS(
		HttpStatus.CONFLICT,
		"LOGIN_ID_ALREADY_EXISTS",
		"The login ID is already in use."
	),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user account was not found."),
	UNSUPPORTED_STOCK(
		HttpStatus.NOT_FOUND,
		"UNSUPPORTED_STOCK",
		"The stock is outside the supported 75-stock universe."
	),
	NOTIFICATION_NOT_FOUND(
		HttpStatus.NOT_FOUND,
		"NOTIFICATION_NOT_FOUND",
		"The notification was not found."
	),
	NEWS_NOT_FOUND(HttpStatus.NOT_FOUND, "NEWS_NOT_FOUND", "The news article was not found."),
	INVALID_NEWS_SELECTION(
		HttpStatus.BAD_REQUEST,
		"INVALID_NEWS_SELECTION",
		"The selected text does not belong to this article."
	),
	AI_RATE_LIMITED(
		HttpStatus.TOO_MANY_REQUESTS,
		"AI_RATE_LIMITED",
		"The AI request limit was reached. Try again later."
	),
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
