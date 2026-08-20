package com.kmarket.navigator.backend.disclosure.application.port;

public record StoredDocumentArchive(
	DocumentArchiveKind kind,
	DocumentArchiveStatus status,
	String relativePath,
	String sha256,
	long sizeBytes,
	String errorCode
) {
}
