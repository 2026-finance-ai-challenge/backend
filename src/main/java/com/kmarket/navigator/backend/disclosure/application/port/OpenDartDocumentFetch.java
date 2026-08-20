package com.kmarket.navigator.backend.disclosure.application.port;

import java.util.List;

public record OpenDartDocumentFetch(
	List<OpenDartDocument> documents,
	List<OpenDartSource> sources
) {
	public OpenDartDocumentFetch {
		documents = List.copyOf(documents);
		sources = List.copyOf(sources);
	}
}
