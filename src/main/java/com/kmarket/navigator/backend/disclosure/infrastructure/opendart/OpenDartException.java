package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGatewayException;

public class OpenDartException extends OpenDartGatewayException {

	private static final long serialVersionUID = 1L;

	OpenDartException(String errorCode) {
		super(errorCode);
	}
}
