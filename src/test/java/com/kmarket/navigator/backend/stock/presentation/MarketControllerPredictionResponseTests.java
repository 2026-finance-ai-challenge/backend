package com.kmarket.navigator.backend.stock.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.stock.domain.ForeignLimitPrediction;

class MarketControllerPredictionResponseTests {

	@Test
	void exposesNextSessionPredictionWhileMarketIsClosed() {
		ForeignLimitPrediction prediction = new ForeignLimitPrediction(
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

		MarketController.ForeignLimitPredictionResponse response =
			MarketController.ForeignLimitPredictionResponse.from(prediction, true, Instant.parse("2026-08-31T10:00:00Z"));

		assertThat(response.status()).isEqualTo("AVAILABLE");
		assertThat(response.minRate()).isEqualByComparingTo("24.1");
		assertThat(response.baseRate()).isEqualByComparingTo("24.2");
		assertThat(response.maxRate()).isEqualByComparingTo("24.3");
		assertThat(response.targetDate()).isEqualTo(LocalDate.of(2026, 9, 1));
		assertThat(response.predictionSession()).isEqualTo("NEXT_SESSION");
		assertThat(response.observationCount()).isEqualTo(120);
		assertThat(response.modelVersion()).isEqualTo("kmarket-foreign-owned-quantity-ml-v2");
		var intraday = MarketController.ForeignLimitPredictionResponse.from(prediction, true, Instant.parse("2026-09-01T02:00:00Z"));
		assertThat(intraday.status()).isEqualTo("AVAILABLE");
		assertThat(intraday.predictionSession()).isEqualTo("INTRADAY");
		assertThat(intraday.targetDate()).isEqualTo(response.targetDate());
		var expired = MarketController.ForeignLimitPredictionResponse.from(prediction, true, Instant.parse("2026-09-01T07:00:00Z"));
		assertThat(expired.status()).isEqualTo("STALE");
		assertThat(expired.baseRate()).isNull();
	}

	@Test
	void marksPredictionAsNotApplicableForStocksWithoutAnAcquisitionLimit() {
		MarketController.ForeignLimitPredictionResponse response =
			MarketController.ForeignLimitPredictionResponse.from(null, false);

		assertThat(response.status()).isEqualTo("NOT_APPLICABLE");
		assertThat(response.source()).isEqualTo("NOT_APPLICABLE");
		assertThat(response.baseRate()).isNull();
		assertThat(response.observationCount()).isZero();
	}
}
