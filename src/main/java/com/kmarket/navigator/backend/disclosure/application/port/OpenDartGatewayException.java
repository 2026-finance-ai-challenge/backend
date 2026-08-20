package com.kmarket.navigator.backend.disclosure.application.port;

public class OpenDartGatewayException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String errorCode;

	public OpenDartGatewayException(String errorCode) {
		super("OpenDART request failed");
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}
}
