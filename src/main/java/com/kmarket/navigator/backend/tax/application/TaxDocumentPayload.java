package com.kmarket.navigator.backend.tax.application;

import java.util.Arrays;

import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;

public record TaxDocumentPayload(
	TaxDocumentType documentType,
	String fileName,
	String mediaType,
	byte[] content
) {

	public TaxDocumentPayload {
		content = content.clone();
	}

	@Override
	public byte[] content() {
		return content.clone();
	}

	public void clear() {
		Arrays.fill(content, (byte) 0);
	}
}
