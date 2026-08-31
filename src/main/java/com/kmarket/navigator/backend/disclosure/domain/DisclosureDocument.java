package com.kmarket.navigator.backend.disclosure.domain;

import java.util.List;
import java.util.UUID;

public record DisclosureDocument(
	UUID id,
	String sourceFilename,
	int version,
	String contentHash,
	String originalHtml,
	List<DisclosureSection> sections
) {
	public DisclosureDocument {
		sections = List.copyOf(sections);
	}
}
