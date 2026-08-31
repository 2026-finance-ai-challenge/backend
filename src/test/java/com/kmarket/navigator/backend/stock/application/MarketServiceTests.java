package com.kmarket.navigator.backend.stock.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.stock.application.port.MarketRepository;
import com.kmarket.navigator.backend.stock.domain.ExchangeRateSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketDataStatus;

class MarketServiceTests {

	private final MarketRepository repository = mock(MarketRepository.class);
	private final MarketService service = new MarketService(
		repository,
		mock(ForeignLimitPredictionEngine.class),
		Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC)
	);

	@Test
	void returnsStoredExchangeRateUsingNormalizedCurrency() {
		var snapshot = new ExchangeRateSnapshot(
			"USD",
			new BigDecimal("1391.20"),
			MarketDataStatus.LIVE,
			Instant.parse("2026-08-31T00:00:00Z"),
			"KIS_REST_EXCHANGE_RATE"
		);
		when(repository.findExchangeRate("USD")).thenReturn(Optional.of(snapshot));

		assertThat(service.exchangeRate(" usd ")).isEqualTo(snapshot);
	}

	@Test
	void returnsUnavailableBoundaryWhenRateIsMissing() {
		when(repository.findExchangeRate("USD")).thenReturn(Optional.empty());

		assertThat(service.exchangeRate("USD")).isNull();
	}
}
