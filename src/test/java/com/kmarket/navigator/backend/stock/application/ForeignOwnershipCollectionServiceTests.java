package com.kmarket.navigator.backend.stock.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.stock.application.port.ForeignOwnershipGateway;
import com.kmarket.navigator.backend.stock.application.port.ForeignLimitPredictionGateway;
import com.kmarket.navigator.backend.stock.application.port.MarketDataGateway;
import com.kmarket.navigator.backend.stock.application.port.MarketSnapshotRepository;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitCollectionTarget;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketDailyPrice;

class ForeignOwnershipCollectionServiceTests {

	@Test
	void usesLatestTradingDateForKisCurrentOwnership() {
		ForeignOwnershipGateway krxGateway = mock(ForeignOwnershipGateway.class);
		MarketDataGateway kisGateway = mock(MarketDataGateway.class);
		MarketSnapshotRepository repository = mock(MarketSnapshotRepository.class);
		ForeignLimitPredictionGateway predictionGateway = mock(ForeignLimitPredictionGateway.class);
		ForeignLimitCollectionTarget target = new ForeignLimitCollectionTarget("003490", "KR7003490000");
		ForeignOwnershipSnapshot snapshot = new ForeignOwnershipSnapshot(
			61_250_000L,
			250_000_000L,
			122_500_000L,
			61_250_000L,
			new BigDecimal("24.5000"),
			new BigDecimal("50.0000"),
			LocalDate.of(2026, 8, 31),
			Instant.parse("2026-08-31T00:00:00Z"),
			"KIS_REST_CURRENT_PRICE"
		);
		when(krxGateway.configured()).thenReturn(false);
		when(kisGateway.configured()).thenReturn(true);
		when(repository.findForeignLimitTargets()).thenReturn(List.of(target));
		when(kisGateway.fetchForeignOwnership("003490")).thenReturn(Optional.of(snapshot));
		when(kisGateway.fetchDailyPrices(
			"003490",
			LocalDate.of(2026, 8, 17),
			LocalDate.of(2026, 8, 31)
		)).thenReturn(List.of(new MarketDailyPrice(
			LocalDate.of(2026, 8, 28),
			BigDecimal.ONE,
			BigDecimal.ONE,
			BigDecimal.ONE,
			BigDecimal.ONE,
			1L,
			"KIS_REST_DAILY_PRICE"
		)));
		when(repository.findForeignOwnershipHistory("003490", 120)).thenReturn(List.of());
		ForeignOwnershipCollectionService service = new ForeignOwnershipCollectionService(
			krxGateway,
			kisGateway,
			repository,
			predictionGateway,
			Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC)
		);

		service.collect();

		verify(kisGateway).fetchForeignOwnership("003490");
		verify(repository).saveForeignOwnership("003490", new ForeignOwnershipSnapshot(
			61_250_000L,
			250_000_000L,
			122_500_000L,
			61_250_000L,
			new BigDecimal("24.5000"),
			new BigDecimal("50.0000"),
			LocalDate.of(2026, 8, 28),
			Instant.parse("2026-08-31T00:00:00Z"),
			"KIS_REST_CURRENT_PRICE"
		));
		verify(predictionGateway).predict("003490", List.of());
		verify(krxGateway, never()).fetchHistory(target, LocalDate.of(2026, 7, 17), LocalDate.of(2026, 8, 31));
	}
}
