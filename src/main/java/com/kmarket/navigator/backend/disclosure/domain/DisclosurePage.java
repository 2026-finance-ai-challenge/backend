package com.kmarket.navigator.backend.disclosure.domain;

import java.util.List;

public record DisclosurePage(List<DisclosureSummary> items, String nextCursor) {

	public DisclosurePage {
		items = List.copyOf(items);
	}
}
