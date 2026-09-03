package com.kmarket.navigator.backend.disclosure.application.port;

import java.util.Arrays;

public record OpenDartSource(
	DocumentArchiveKind kind,
	DocumentArchiveStatus status,
	byte[] content,
	String errorCode,
	java.util.Map<String, byte[]> provenance
) {
	public OpenDartSource(DocumentArchiveKind kind, DocumentArchiveStatus status, byte[] content, String errorCode) {
		this(kind, status, content, errorCode, java.util.Map.of());
	}

	public OpenDartSource {
		if (kind == null || status == null || content == null || content.length == 0) {
			throw new IllegalArgumentException("Document source must contain a non-empty payload");
		}
		content = content.clone();
		provenance = copyProvenance(provenance);
		errorCode = errorCode == null || errorCode.isBlank() ? null : errorCode;
	}

	@Override
	public byte[] content() {
		return Arrays.copyOf(content, content.length);
	}

	@Override
	public java.util.Map<String, byte[]> provenance() { return copyProvenance(provenance); }

	private static java.util.Map<String, byte[]> copyProvenance(java.util.Map<String, byte[]> values) {
		var copy = new java.util.LinkedHashMap<String, byte[]>();
		values.forEach((key, value) -> {
			if (!key.matches("[A-Za-z0-9_-]{1,50}\\.raw")) throw new IllegalArgumentException("Invalid source entry name");
			copy.put(key, value.clone());
		});
		return java.util.Collections.unmodifiableMap(copy);
	}
}
