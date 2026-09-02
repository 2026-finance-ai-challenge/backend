package com.kmarket.navigator.backend.stock.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.stock.application.MarketChartService.MarketChartPeriod;
import com.kmarket.navigator.backend.stock.application.port.MarketDataGateway;
import com.kmarket.navigator.backend.stock.domain.MarketDataStatus;
import com.kmarket.navigator.backend.stock.domain.MarketIntradayPrice;

class MarketChartServiceTests {

	@Test
	void aggregatesTodayTicksIntoTenMinuteOhlcvBars() {
		MarketService marketService = mock(MarketService.class);
		MarketDataGateway gateway = mock(MarketDataGateway.class);
		when(marketService.history(anyString(), any(LocalDate.class), any(LocalDate.class), anyInt()))
			.thenReturn(new MarketHistory("005930", MarketDataStatus.LIVE, List.of()));
		ZoneId korea = ZoneId.of("Asia/Seoul");
		LocalDate today = LocalDate.now(korea);
		when(gateway.fetchIntradayPrices(anyString(), any(LocalDate.class), any(LocalDate.class)))
			.thenReturn(List.of(
				price(today.atTime(9, 1), "100", "102", "99", "101", 10, korea),
				price(today.atTime(9, 8), "101", "105", "100", "104", 20, korea),
				price(today.atTime(9, 11), "104", "106", "103", "105", 30, korea)
			));

		var chart = new MarketChartService(marketService, gateway).chart("005930", MarketChartPeriod.ONE_DAY);

		assertThat(chart.intervalMinutes()).isEqualTo(10);
		assertThat(chart.items()).hasSize(2);
		assertThat(chart.items().getFirst().openPriceKrw()).isEqualByComparingTo("100");
		assertThat(chart.items().getFirst().highPriceKrw()).isEqualByComparingTo("105");
		assertThat(chart.items().getFirst().lowPriceKrw()).isEqualByComparingTo("99");
		assertThat(chart.items().getFirst().closePriceKrw()).isEqualByComparingTo("104");
		assertThat(chart.items().getFirst().volume()).isEqualTo(30L);
	}

	private static MarketIntradayPrice price(
		LocalDateTime time,
		String open,
		String high,
		String low,
		String close,
		long volume,
		ZoneId zone
	) {
		return new MarketIntradayPrice(
			time.atZone(zone).toInstant(),
			new BigDecimal(open),
			new BigDecimal(high),
			new BigDecimal(low),
			new BigDecimal(close),
			volume,
			"TEST"
		);
	}
}
