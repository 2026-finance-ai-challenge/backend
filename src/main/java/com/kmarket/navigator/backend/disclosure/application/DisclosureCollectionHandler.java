package com.kmarket.navigator.backend.disclosure.application;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGateway;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartPage;
import com.kmarket.navigator.backend.disclosure.domain.CorporationClass;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;

@Service
public class DisclosureCollectionHandler {

	private static final int MAX_PAGES_PER_QUERY = 100;
	private static final List<CorporationClass> SUPPORTED_MARKETS = List.of(
		CorporationClass.KOSPI,
		CorporationClass.KOSDAQ
	);

	private final OpenDartGateway openDartGateway;
	private final DisclosureRepository disclosureRepository;

	public DisclosureCollectionHandler(
		OpenDartGateway openDartGateway,
		DisclosureRepository disclosureRepository
	) {
		this.openDartGateway = openDartGateway;
		this.disclosureRepository = disclosureRepository;
	}

	public int collect(LocalDate date) {
		int saved = 0;
		for (CorporationClass corporationClass : SUPPORTED_MARKETS) {
			for (DisclosureType disclosureType : DisclosureType.values()) {
				saved += collect(date, corporationClass, disclosureType);
			}
		}
		return saved;
	}

	public void synchronizeCorporations() {
		disclosureRepository.upsertCorporations(openDartGateway.fetchListedCorporations());
	}

	private int collect(
		LocalDate date,
		CorporationClass corporationClass,
		DisclosureType disclosureType
	) {
		int saved = 0;
		int page = 1;
		int totalPages;
		do {
			OpenDartPage response = openDartGateway.fetchFilings(
				date,
				corporationClass,
				disclosureType,
				page
			);
			if (response.totalPages() > MAX_PAGES_PER_QUERY) {
				throw new IllegalStateException("OpenDART page limit exceeded");
			}
			for (var filing : response.filings()) {
				if (disclosureRepository.saveFiling(filing)) {
					saved++;
				}
			}
			totalPages = response.totalPages();
			page++;
		}
		while (page <= totalPages);
		return saved;
	}
}
