package com.kmarket.navigator.backend.disclosure.application.port;

import java.util.List;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureInsightEvidence;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureInsightGeneration;

public interface DisclosureInsightGateway {

	DisclosureInsightGeneration summarize(
		String receiptNumber,
		String title,
		List<DisclosureInsightEvidence> evidence
	);
}
