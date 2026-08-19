package com.kmarket.navigator.backend.disclosure.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.application.port.ListedStockGateway;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartFiling;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGateway;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartPage;
import com.kmarket.navigator.backend.disclosure.domain.CorporationClass;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;

class DisclosureCollectionHandlerTests {

	private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
	private static final LocalDate TO = LocalDate.of(2026, 3, 31);

	@Test
	void savesOnlyActiveCommonStockFilings() {
		OpenDartGateway openDartGateway = mock(OpenDartGateway.class);
		ListedStockGateway listedStockGateway = mock(ListedStockGateway.class);
		DisclosureRepository repository = mock(DisclosureRepository.class);
		OpenDartFiling commonStockFiling = filing("20260101000001", "005930");
		OpenDartFiling excludedFiling = filing("20260101000002", "005935");
		when(repository.findActiveCommonStockCodes()).thenReturn(Set.of("005930"));
		when(openDartGateway.fetchFilings(any(), any(), any(), any(), anyInt()))
			.thenReturn(new OpenDartPage(List.of(), 1, 1));
		when(openDartGateway.fetchFilings(
			eq(FROM),
			eq(TO),
			eq(CorporationClass.KOSPI),
			eq(DisclosureType.PERIODIC),
			eq(1)
		)).thenReturn(new OpenDartPage(List.of(commonStockFiling, excludedFiling), 1, 1));
		when(repository.saveFilings(List.of(commonStockFiling))).thenReturn(1);

		int saved = new DisclosureCollectionHandler(
			openDartGateway,
			listedStockGateway,
			repository
		).collect(FROM, TO);

		assertThat(saved).isEqualTo(1);
		verify(repository).saveFilings(List.of(commonStockFiling));
	}

	@Test
	void collectsCommonStocksClassifiedAsOtherByOpenDart() {
		OpenDartGateway openDartGateway = mock(OpenDartGateway.class);
		ListedStockGateway listedStockGateway = mock(ListedStockGateway.class);
		DisclosureRepository repository = mock(DisclosureRepository.class);
		OpenDartFiling filing = new OpenDartFiling(
			"20260101000003",
			"00351454",
			"더테크놀로지",
			"043090",
			CorporationClass.OTHER,
			DisclosureType.PERIODIC,
			"사업보고서",
			"더테크놀로지",
			FROM,
			""
		);
		when(repository.findActiveCommonStockCodes()).thenReturn(Set.of("043090"));
		when(openDartGateway.fetchFilings(any(), any(), any(), any(), anyInt()))
			.thenReturn(new OpenDartPage(List.of(), 1, 1));
		when(openDartGateway.fetchFilings(
			eq(FROM),
			eq(TO),
			eq(CorporationClass.OTHER),
			eq(DisclosureType.PERIODIC),
			eq(1)
		)).thenReturn(new OpenDartPage(List.of(filing), 1, 1));
		when(repository.saveFilings(List.of(filing))).thenReturn(1);

		int saved = new DisclosureCollectionHandler(
			openDartGateway,
			listedStockGateway,
			repository
		).collect(FROM, TO);

		assertThat(saved).isEqualTo(1);
		verify(repository).saveFilings(List.of(filing));
	}

	@Test
	void rejectsRangesLongerThanThreeMonths() {
		OpenDartGateway openDartGateway = mock(OpenDartGateway.class);
		ListedStockGateway listedStockGateway = mock(ListedStockGateway.class);
		DisclosureRepository repository = mock(DisclosureRepository.class);
		DisclosureCollectionHandler handler = new DisclosureCollectionHandler(
			openDartGateway,
			listedStockGateway,
			repository
		);

		assertThatThrownBy(() -> handler.collect(FROM, LocalDate.of(2026, 4, 1)))
			.isInstanceOf(IllegalArgumentException.class);
		verify(repository, org.mockito.Mockito.never()).findActiveCommonStockCodes();
	}

	private static OpenDartFiling filing(String receiptNumber, String stockCode) {
		return new OpenDartFiling(
			receiptNumber,
			"00126380",
			"테스트",
			stockCode,
			CorporationClass.KOSPI,
			DisclosureType.PERIODIC,
			"사업보고서",
			"테스트",
			FROM,
			""
		);
	}
}
