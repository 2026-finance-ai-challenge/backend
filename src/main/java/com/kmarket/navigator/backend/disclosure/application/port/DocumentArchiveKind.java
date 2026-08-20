package com.kmarket.navigator.backend.disclosure.application.port;

public enum DocumentArchiveKind {
	OPENDART_ZIP("api"),
	DART_VIEWER_HTML("viewer");

	private final String fileSuffix;

	DocumentArchiveKind(String fileSuffix) {
		this.fileSuffix = fileSuffix;
	}

	public String fileSuffix() {
		return fileSuffix;
	}
}
