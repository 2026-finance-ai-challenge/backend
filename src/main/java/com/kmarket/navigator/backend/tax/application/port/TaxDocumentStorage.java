package com.kmarket.navigator.backend.tax.application.port;

import java.util.UUID;

public interface TaxDocumentStorage {

	String store(UUID userId, UUID documentId, String sha256, String mediaType, byte[] content);

	byte[] read(UUID userId, UUID documentId, String sha256, String mediaType, String storageKey);

	void delete(String storageKey);
}
