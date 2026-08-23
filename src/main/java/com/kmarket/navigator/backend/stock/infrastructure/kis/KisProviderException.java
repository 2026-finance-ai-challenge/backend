package com.kmarket.navigator.backend.stock.infrastructure.kis;

class KisProviderException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	KisProviderException(String message) {
		super(message);
	}

	KisProviderException(String message, Throwable cause) {
		super(message, cause);
	}
}
