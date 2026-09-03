package com.kmarket.navigator.backend.stock.infrastructure.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.stock.application.port.MarketSnapshotRepository;
import com.kmarket.navigator.backend.stock.domain.MarketIndexSnapshot;

import tools.jackson.databind.ObjectMapper;

class KisRealtimeMarketServiceTests {

	private KisRealtimeMarketService service;

	@AfterEach
	void closeTransport() {
		if (service != null) service.stop();
	}

	@Test
	void rotatesTheLeastRecentlyUsedStockAtTheConfiguredCapacity() {
		KisMarketProperties properties = configuredProperties();
		properties.setMaxRealtimeStocks(2);
		service = service(properties, mock(MarketSnapshotRepository.class));

		service.subscribeStock("005930");
		service.subscribeStock("000660");
		service.subscribeStock("005930");
		var result = service.subscribeStock("035420");

		assertThat(result.rotatedOutStockCode()).isEqualTo("000660");
		assertThat(result.activeCount()).isEqualTo(2);
	}

	@Test
	void parsesTheOfficialKospiRealtimeIndexFields() {
		MarketSnapshotRepository repository = mock(MarketSnapshotRepository.class);
		service = service(configuredProperties(), repository);
		String[] fields = new String[30];
		java.util.Arrays.fill(fields, "0");
		fields[0] = "0001";
		fields[1] = "101530";
		fields[2] = "2850.50";
		fields[3] = "2";
		fields[4] = "10.20";
		fields[5] = "500000000";
		fields[9] = "0.3591";
		fields[10] = "2842.00";
		fields[11] = "2860.00";
		fields[12] = "2835.00";

		service.accept("0|H0UPCNT0|001|" + String.join("^", fields));

		ArgumentCaptor<MarketIndexSnapshot> captor = ArgumentCaptor.forClass(MarketIndexSnapshot.class);
		verify(repository).saveIndex(captor.capture());
		assertThat(captor.getValue().indexName()).isEqualTo("KOSPI");
		assertThat(captor.getValue().currentValue()).isEqualByComparingTo("2850.50");
		assertThat(captor.getValue().changeRate()).isEqualByComparingTo("0.3591");
	}

	@Test
	void keepsAlreadySignedRealtimeDecreaseNegative() {
		assertThat(KisRealtimeMarketService.signed(new BigDecimal("-8500"), "5"))
			.isEqualByComparingTo("-8500");
		assertThat(KisRealtimeMarketService.signed(new BigDecimal("8500"), "5"))
			.isEqualByComparingTo("-8500");
	}

	private static KisRealtimeMarketService service(
		KisMarketProperties properties,
		MarketSnapshotRepository repository
	) {
		return new KisRealtimeMarketService(
			RestClient.builder().baseUrl("https://example.test").build(),
			properties,
			repository,
			new ObjectMapper(),
			java.time.Clock.fixed(java.time.Instant.parse("2026-09-03T01:15:30Z"), java.time.ZoneOffset.UTC)
		);
	}

	private static KisMarketProperties configuredProperties() {
		KisMarketProperties properties = new KisMarketProperties();
		properties.setEnabled(true);
		properties.setAppKey("app-key");
		properties.setAppSecret("app-secret");
		return properties;
	}
}
