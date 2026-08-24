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
	CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_ROOM_NOT_FOUND", "The chat room was not found."),
	INVALID_CHAT_CONTEXT(
		HttpStatus.BAD_REQUEST,
		"INVALID_CHAT_CONTEXT",
		"The requested chat context is invalid or unavailable."
	),
	INVALID_CHAT_ROOM_NAME(
		HttpStatus.BAD_REQUEST,
		"INVALID_CHAT_ROOM_NAME",
		"The chat room name is invalid."
	),
	CHAT_ROOM_VERSION_CONFLICT(
		HttpStatus.CONFLICT,
		"CHAT_ROOM_VERSION_CONFLICT",
		"The chat room was changed by another request. Reload and try again."
	),
	CHAT_MESSAGE_NOT_FOUND(
		HttpStatus.NOT_FOUND,
		"CHAT_MESSAGE_NOT_FOUND",
		"The chat message was not found."
	),
	CHAT_GENERATION_NOT_FOUND(
		HttpStatus.NOT_FOUND,
		"CHAT_GENERATION_NOT_FOUND",
		"The chat generation was not found."
	),
	INVALID_CHAT_MESSAGE(
		HttpStatus.BAD_REQUEST,
		"INVALID_CHAT_MESSAGE",
		"The chat message is invalid."
	),
	INVALID_CHAT_SELECTION(
		HttpStatus.BAD_REQUEST,
		"INVALID_CHAT_SELECTION",
		"The selected filing context is invalid."
	),
	CHAT_IDEMPOTENCY_CONFLICT(
		HttpStatus.CONFLICT,
		"CHAT_IDEMPOTENCY_CONFLICT",
		"The request key was already used with different content."
	),
	CHAT_GENERATION_NOT_STOPPABLE(
		HttpStatus.CONFLICT,
		"CHAT_GENERATION_NOT_STOPPABLE",
		"The generation is no longer running."
	),
	CHAT_GENERATION_NOT_RETRYABLE(
		HttpStatus.CONFLICT,
		"CHAT_GENERATION_NOT_RETRYABLE",
		"Only a failed generation can be retried."
	),
	CHAT_CONTEXT_STALE(
		HttpStatus.CONFLICT,
		"CHAT_CONTEXT_STALE",
		"The bound filing version is no longer current. Start a new filing chat."
	),
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
	TRANSLATION_NOT_FOUND(
		HttpStatus.NOT_FOUND,
		"TRANSLATION_NOT_FOUND",
		"A translation has not been requested for the current source version."
	),
	SOURCE_CONTENT_UNAVAILABLE(
		HttpStatus.CONFLICT,
		"SOURCE_CONTENT_UNAVAILABLE",
		"The source content is unavailable for translation."
	),
	TRANSLATION_GENERATION_IN_PROGRESS(
		HttpStatus.CONFLICT,
		"TRANSLATION_GENERATION_IN_PROGRESS",
		"The same source translation is already being generated."
	),
	GLOBAL_PEER_DATA_UNAVAILABLE(
		HttpStatus.NOT_FOUND,
		"GLOBAL_PEER_DATA_UNAVAILABLE",
		"Validated global peer data is not available for this stock."
	),
	GLOBAL_PEER_GENERATION_IN_PROGRESS(
		HttpStatus.CONFLICT,
		"GLOBAL_PEER_GENERATION_IN_PROGRESS",
		"Global peer analysis is already being generated. Try again shortly."
	),
	INVALID_TAX_DOCUMENT(
		HttpStatus.BAD_REQUEST,
		"INVALID_TAX_DOCUMENT",
		"The tax document file, type, or signature is invalid."
	),
	TAX_DOCUMENT_NOT_FOUND(
		HttpStatus.NOT_FOUND,
		"TAX_DOCUMENT_NOT_FOUND",
		"The tax document was not found."
	),
	TAX_DOCUMENT_NOT_RETRYABLE(
		HttpStatus.CONFLICT,
		"TAX_DOCUMENT_NOT_RETRYABLE",
		"Only a failed tax document verification can be retried."
	),
	TAX_DOCUMENT_RATE_LIMITED(
		HttpStatus.TOO_MANY_REQUESTS,
		"TAX_DOCUMENT_RATE_LIMITED",
		"The tax document upload limit was reached. Try again later."
	),
	TAX_DOCUMENT_STORAGE_UNAVAILABLE(
		HttpStatus.SERVICE_UNAVAILABLE,
		"TAX_DOCUMENT_STORAGE_UNAVAILABLE",
		"Secure tax document storage is temporarily unavailable."
	),
	DISCLOSURE_NOT_FOUND(HttpStatus.NOT_FOUND, "DISCLOSURE_NOT_FOUND", "The disclosure was not found."),
	DISCLOSURE_SECTION_NOT_FOUND(
		HttpStatus.NOT_FOUND,
		"DISCLOSURE_SECTION_NOT_FOUND",
		"The section does not belong to the current disclosure document version."
	),
	DISCLOSURE_INDEX_NOT_READY(
		HttpStatus.CONFLICT,
		"DISCLOSURE_INDEX_NOT_READY",
		"The disclosure is not ready for questions."
	),
	DISCLOSURE_DOCUMENT_NOT_READY(
		HttpStatus.CONFLICT,
		"DISCLOSURE_DOCUMENT_NOT_READY",
		"The disclosure document is not ready for AI insight."
	),
	DISCLOSURE_INSIGHT_NOT_READY(
		HttpStatus.NOT_FOUND,
		"DISCLOSURE_INSIGHT_NOT_READY",
		"An AI insight has not been generated for the current document version."
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
