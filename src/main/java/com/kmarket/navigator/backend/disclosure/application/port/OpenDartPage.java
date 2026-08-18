package com.kmarket.navigator.backend.disclosure.application.port;

import java.util.List;

public record OpenDartPage(List<OpenDartFiling> filings, int page, int totalPages) {
	public OpenDartPage {
		filings = List.copyOf(filings);
	}
}
