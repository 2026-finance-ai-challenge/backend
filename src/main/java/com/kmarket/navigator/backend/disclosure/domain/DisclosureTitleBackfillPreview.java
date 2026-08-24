package com.kmarket.navigator.backend.disclosure.domain;

import java.util.List;

public record DisclosureTitleBackfillPreview(
	long disclosureCount,
	long uniqueTitleCount,
	long readyTitleCount,
	long catalogMatchCount,
	List<String> missingTitles
) {
	public DisclosureTitleBackfillPreview {
		missingTitles = List.copyOf(missingTitles);
	}

	public long pendingTitleCount() {
		return uniqueTitleCount - readyTitleCount;
	}

	public boolean completeCatalog() {
		return missingTitles.isEmpty();
	}
}
