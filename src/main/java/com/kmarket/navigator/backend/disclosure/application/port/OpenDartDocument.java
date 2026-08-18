package com.kmarket.navigator.backend.disclosure.application.port;

import java.util.List;

public record OpenDartDocument(
	String filename,
	String contentHash,
	String bodyText,
	List<OpenDartSection> sections
) {
	public OpenDartDocument {
		sections = List.copyOf(sections);
	}
}
