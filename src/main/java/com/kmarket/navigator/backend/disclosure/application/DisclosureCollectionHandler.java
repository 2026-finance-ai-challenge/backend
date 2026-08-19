package com.kmarket.navigator.backend.disclosure.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.application.port.ListedStockGateway;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGateway;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartPage;
import com.kmarket.navigator.backend.disclosure.domain.CorporationClass;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;

@Service
public class DisclosureCollectionHandler {

	private static final int MAX_PAGES_PER_QUERY = 1_000;
	private static final List<CorporationClass> DART_CORPORATION_CLASSES = List.of(
		CorporationClass.values()
	);

	private final OpenDartGateway openDartGateway;
	private final ListedStockGateway listedStockGateway;
	private final DisclosureRepository disclosureRepository;

	public DisclosureCollectionHandler(
		OpenDartGateway openDartGateway,
		ListedStockGateway listedStockGateway,
		DisclosureRepository disclosureRepository
	) {
		this.openDartGateway = openDartGateway;
		this.listedStockGateway = listedStockGateway;
		this.disclosureRepository = disclosureRepository;
	}

	public int collect(LocalDate date) {
		return collect(date, date);
	}

	public int collect(LocalDate from, LocalDate to) {
		if (from.isAfter(to) || to.isAfter(from.plusMonths(3).minusDays(1))) {
			throw new IllegalArgumentException("Disclosure collection range must be within three months");
		}
		Set<String> commonStockCodes = disclosureRepository.findActiveCommonStockCodes();
		if (commonStockCodes.isEmpty()) {
			throw new IllegalStateException("Common stock universe is empty");
		}
		int saved = 0;
		for (CorporationClass corporationClass : DART_CORPORATION_CLASSES) {
			for (DisclosureType disclosureType : DisclosureType.values()) {
				saved += collect(from, to, corporationClass, disclosureType, commonStockCodes);
			}
		}
		return saved;
	}

	public void synchronizeCorporations() {
		disclosureRepository.upsertCorporations(openDartGateway.fetchListedCorporations());
		disclosureRepository.replaceCommonStockUniverse(listedStockGateway.fetchCommonStocks());
	}

	private int collect(
		LocalDate from,
		LocalDate to,
		CorporationClass corporationClass,
		DisclosureType disclosureType,
		Set<String> commonStockCodes
	) {
		int saved = 0;
		int page = 1;
		int totalPages;
		do {
			OpenDartPage response = openDartGateway.fetchFilings(
				from,
				to,
				corporationClass,
				disclosureType,
				page
			);
			if (response.totalPages() > MAX_PAGES_PER_QUERY) {
				throw new IllegalStateException("OpenDART page limit exceeded");
			}
			var commonStockFilings = response.filings().stream()
				.filter(filing -> filing.stockCode() != null)
				.filter(filing -> commonStockCodes.contains(filing.stockCode()))
				.toList();
			saved += disclosureRepository.saveFilings(commonStockFilings);
			totalPages = response.totalPages();
			page++;
		}
		while (page <= totalPages);
		return saved;
	}
}
