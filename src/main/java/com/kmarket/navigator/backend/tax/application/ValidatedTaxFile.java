package com.kmarket.navigator.backend.tax.application;

public record ValidatedTaxFile(
	String originalFileName,
	String mediaType,
	byte[] content,
	String sha256
) {
	public ValidatedTaxFile {
		content = content.clone();
	}

	@Override
	public byte[] content() {
		return content.clone();
	}
}
