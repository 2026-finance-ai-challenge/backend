package com.kmarket.navigator.backend.global.error;

import java.util.Objects;
import java.util.Map;

@SuppressWarnings("serial")
public class BusinessException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final ErrorCode errorCode;
	private final Map<String, Object> properties;

	public BusinessException(ErrorCode errorCode) {
		this(errorCode, Map.of());
	}

	public BusinessException(ErrorCode errorCode, Map<String, Object> properties) {
		super(Objects.requireNonNull(errorCode).message());
		this.errorCode = errorCode;
		this.properties = Map.copyOf(properties);
	}

	public ErrorCode errorCode() {
		return errorCode;
	}

	public Map<String, Object> properties() {
		return properties;
	}
}
