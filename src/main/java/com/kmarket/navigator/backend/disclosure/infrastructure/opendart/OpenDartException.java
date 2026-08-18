package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

public class OpenDartException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String errorCode;

	OpenDartException(String errorCode) {
		super("OpenDART request failed");
		this.errorCode = errorCode;
	}

	String errorCode() {
		return errorCode;
	}
}
