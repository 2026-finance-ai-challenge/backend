package com.kmarket.navigator.backend.disclosure.application.port;

@SuppressWarnings("serial")
public class OpenDartSourceException extends OpenDartGatewayException {

	private static final long serialVersionUID = 1L;

	private final OpenDartSource source;

	public OpenDartSourceException(String errorCode, OpenDartSource source) {
		super(errorCode);
		this.source = source;
	}

	public OpenDartSource source() {
		return source;
	}
}
