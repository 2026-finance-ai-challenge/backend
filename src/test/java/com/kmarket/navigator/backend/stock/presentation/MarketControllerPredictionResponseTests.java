package com.kmarket.navigator.backend.stock.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.stock.domain.ForeignLimitPrediction;

class MarketControllerPredictionResponseTests {

	@Test
	void preservesObservationMetadataWhileMarketIsClosed() {
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
			MarketController.ForeignLimitPredictionResponse.from(prediction, "CLOSED", true);

		assertThat(response.status()).isEqualTo("MARKET_CLOSED");
		assertThat(response.minRate()).isNull();
		assertThat(response.baseRate()).isNull();
		assertThat(response.maxRate()).isNull();
		assertThat(response.observationCount()).isEqualTo(120);
		assertThat(response.modelVersion()).isEqualTo("kmarket-foreign-owned-quantity-ml-v2");
	}

	@Test
	void marksPredictionAsNotApplicableForStocksWithoutAnAcquisitionLimit() {
		MarketController.ForeignLimitPredictionResponse response =
			MarketController.ForeignLimitPredictionResponse.from(null, "OPEN", false);

		assertThat(response.status()).isEqualTo("NOT_APPLICABLE");
		assertThat(response.source()).isEqualTo("NOT_APPLICABLE");
		assertThat(response.baseRate()).isNull();
		assertThat(response.observationCount()).isZero();
	}
}
