package com.kmarket.navigator.backend.disclosure.application.port;

public class ListedStockGatewayException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ListedStockGatewayException(String message) {
		super(message);
	}

	public ListedStockGatewayException(String message, Throwable cause) {
		super(message, cause);
	}
}
