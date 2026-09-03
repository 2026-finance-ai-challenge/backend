package com.kmarket.navigator.backend.stock.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.stock.application.port.MarketRepository;
import com.kmarket.navigator.backend.stock.application.port.ForeignLimitPredictionGateway;
import com.kmarket.navigator.backend.stock.domain.ExchangeRateSnapshot;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPolicy;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPrediction;
import com.kmarket.navigator.backend.stock.domain.MarketDataStatus;
import com.kmarket.navigator.backend.stock.domain.MarketForeignNetFlowSummary;
import com.kmarket.navigator.backend.stock.domain.MarketQuoteSnapshot;
import com.kmarket.navigator.backend.stock.domain.PriceLimitState;
import com.kmarket.navigator.backend.stock.domain.StockIdentity;
import com.kmarket.navigator.backend.stock.domain.StockMarketView;

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

	@Test
	void marksFreshIntradayForeignFlowAsDelayedInsteadOfClosed() {
		var snapshot = new MarketForeignNetFlowSummary(
			LocalDate.of(2026, 9, 2),
			new BigDecimal("-907155000000"),
			4,
			MarketDataStatus.CLOSED,
			Instant.parse("2026-09-02T02:14:00Z"),
			"KIS_REST_INVESTOR_DAILY_BY_MARKET"
		);
		var marketService = new MarketService(
			repository,
			mock(ForeignLimitPredictionEngine.class),
			Clock.fixed(Instant.parse("2026-09-02T02:15:00Z"), ZoneOffset.UTC)
		);
		when(repository.findLatestForeignNetFlow()).thenReturn(Optional.of(snapshot));

		assertThat(marketService.foreignNetFlow().dataStatus())
			.isEqualTo(MarketDataStatus.DELAYED);
	}

	@Test
	void usesStoredModelPredictionBeforeCallingAiAgain() {
		UUID securityId = UUID.randomUUID();
		ForeignLimitPrediction storedPrediction = new ForeignLimitPrediction(
			new BigDecimal("24.100000"),
			new BigDecimal("24.200000"),
			new BigDecimal("24.300000"),
			120,
			229,
			new BigDecimal("0.860000"),
			"kmarket-foreign-owned-quantity-ml-v2",
			LocalDate.of(2026, 8, 31),
			Instant.parse("2026-08-31T09:40:00Z"),
			"KMARKET_AI_FOREIGN_OWNED_QUANTITY_ML"
		);
		ForeignLimitPredictionEngine engine = mock(ForeignLimitPredictionEngine.class);
		ForeignLimitPredictionGateway gateway = mock(ForeignLimitPredictionGateway.class);
		MarketService marketService = new MarketService(
			repository,
			engine,
			gateway,
			Clock.fixed(Instant.parse("2026-08-31T09:41:00Z"), ZoneOffset.UTC)
		);
		StockMarketView view = new StockMarketView(
			new StockIdentity(securityId, "003490", "대한항공", "Korean Air", "KOSPI", "Airlines", false),
			new MarketQuoteSnapshot(
				new BigDecimal("24500"), BigDecimal.ZERO, BigDecimal.ZERO,
				new BigDecimal("24500"), new BigDecimal("24500"), new BigDecimal("24500"),
				1L, "REGULAR", false, false, PriceLimitState.NONE, false, null, true,
				MarketDataStatus.LIVE, Instant.parse("2026-08-31T09:40:00Z"), "TEST"
			),
			null
		);
		when(repository.findStock("003490", null)).thenReturn(Optional.of(view));
		when(repository.findExchangeRate("USD")).thenReturn(Optional.empty());
		when(repository.findForeignLimitPolicies()).thenReturn(List.of(
			new ForeignLimitPolicy("003490", new BigDecimal("90"), LocalDate.of(2026, 8, 23))
		));
		when(repository.findForeignOwnershipHistory(securityId, 120)).thenReturn(List.of());
		when(repository.findForeignLimitPredictionBefore(securityId, LocalDate.of(2026, 9, 1)))
			.thenReturn(Optional.of(storedPrediction));

		assertThat(marketService.stockDetail("003490", null).foreignLimitPrediction())
			.isEqualTo(storedPrediction);
		verifyNoInteractions(gateway, engine);

		MarketService intradayService = new MarketService(repository, engine, gateway,
			Clock.fixed(Instant.parse("2026-09-01T03:00:00Z"), ZoneOffset.UTC));
		assertThat(intradayService.stockDetail("003490", null).foreignLimitPrediction()).isEqualTo(storedPrediction);
		verifyNoInteractions(gateway, engine);

		MarketService expiredService = new MarketService(repository, engine, gateway,
			Clock.fixed(Instant.parse("2026-09-01T07:00:00Z"), ZoneOffset.UTC));
		assertThat(expiredService.stockDetail("003490", null).foreignLimitPrediction()).isNull();
		verifyNoInteractions(gateway, engine);
	}
}
