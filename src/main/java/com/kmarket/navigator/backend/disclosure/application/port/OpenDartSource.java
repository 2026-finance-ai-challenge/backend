package com.kmarket.navigator.backend.disclosure.application.port;

import java.util.Arrays;

public record OpenDartSource(
	DocumentArchiveKind kind,
	DocumentArchiveStatus status,
	byte[] content,
	String errorCode
) {
	public OpenDartSource {
		if (kind == null || status == null || content == null || content.length == 0) {
			throw new IllegalArgumentException("Document source must contain a non-empty payload");
		}
		content = content.clone();
		errorCode = errorCode == null || errorCode.isBlank() ? null : errorCode;
	}

	@Override
	public byte[] content() {
		return Arrays.copyOf(content, content.length);
	}
}
