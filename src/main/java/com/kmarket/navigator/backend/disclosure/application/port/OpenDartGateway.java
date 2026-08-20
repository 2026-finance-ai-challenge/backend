package com.kmarket.navigator.backend.disclosure.application.port;

import java.time.LocalDate;
import java.util.List;

import com.kmarket.navigator.backend.disclosure.domain.CorporationClass;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;

public interface OpenDartGateway {

	OpenDartPage fetchFilings(
		LocalDate from,
		LocalDate to,
		CorporationClass corporationClass,
		DisclosureType disclosureType,
		int page
	);

	List<OpenDartCorporation> fetchListedCorporations();

	OpenDartDocumentFetch fetchDocuments(String receiptNumber);
}
