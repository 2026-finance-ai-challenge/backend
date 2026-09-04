package com.kmarket.navigator.backend.disclosure.application.port;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureAnswer;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureQuestion;

public interface DisclosureRagGateway {

	DisclosureAnswer ask(String receiptNumber, DisclosureQuestion question);

	java.util.List<com.kmarket.navigator.backend.disclosure.domain.FilingEvidence> retrieve(
		java.util.List<String> stockCodes, String question, java.time.LocalDate from,
		java.time.LocalDate to, boolean financials);
}
