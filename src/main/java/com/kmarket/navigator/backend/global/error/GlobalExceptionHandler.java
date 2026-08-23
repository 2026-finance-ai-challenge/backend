package com.kmarket.navigator.backend.global.error;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.kmarket.navigator.backend.global.config.RequestIdFilter;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<ProblemDetail> handleBusinessException(
		BusinessException exception,
		HttpServletRequest request
	) {
		ResponseEntity<ProblemDetail> response = problem(
			exception.errorCode(),
			request,
			exception.properties()
		);
		Object retryAfter = exception.properties().get("retryAfterSeconds");
		if (retryAfter instanceof Number seconds) {
			return ResponseEntity.status(response.getStatusCode())
				.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds.longValue()))
				.body(response.getBody());
		}
		return response;
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ProblemDetail> handleConstraintViolationException(
		ConstraintViolationException exception,
		HttpServletRequest request
	) {
		return problem(ErrorCode.INVALID_REQUEST, request, null);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpectedException(
		Exception exception,
		HttpServletRequest request
	) {
		log.error("처리하지 못한 서버 오류", exception);
		return problem(ErrorCode.INTERNAL_SERVER_ERROR, request, null);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(
		MethodArgumentNotValidException exception,
		HttpHeaders headers,
		HttpStatusCode status,
		WebRequest request
	) {
		List<Map<String, String>> violations = exception.getBindingResult().getFieldErrors().stream()
			.map(error -> Map.of(
				"field", error.getField(),
				"message", error.getDefaultMessage() == null ? "Invalid value." : error.getDefaultMessage()
			))
			.toList();

		HttpServletRequest servletRequest = (HttpServletRequest) request.resolveReference(WebRequest.REFERENCE_REQUEST);
		return problemObject(ErrorCode.INVALID_REQUEST, servletRequest, Map.of("violations", violations));
	}

	private ResponseEntity<ProblemDetail> problem(
		ErrorCode errorCode,
		HttpServletRequest request,
		Map<String, Object> properties
	) {
		return ResponseEntity
			.status(errorCode.status())
			.body(problemDetail(errorCode, request, properties));
	}

	private ResponseEntity<Object> problemObject(
		ErrorCode errorCode,
		HttpServletRequest request,
		Map<String, Object> properties
	) {
		return ResponseEntity
			.status(errorCode.status())
			.body(problemDetail(errorCode, request, properties));
	}

	private ProblemDetail problemDetail(
		ErrorCode errorCode,
		HttpServletRequest request,
		Map<String, Object> properties
	) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(errorCode.status(), errorCode.message());
		detail.setTitle(errorCode.code());
		detail.setInstance(URI.create(request.getRequestURI()));
		detail.setProperty("code", errorCode.code());
		detail.setProperty("timestamp", Instant.now());
		detail.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
		if (properties != null) {
			properties.forEach(detail::setProperty);
		}
		return detail;
	}
}
