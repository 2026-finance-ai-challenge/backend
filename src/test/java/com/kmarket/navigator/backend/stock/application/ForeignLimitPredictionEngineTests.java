package com.kmarket.navigator.backend.stock.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;

class ForeignLimitPredictionEngineTests {

	@Test
	void predictsInOwnershipPercentageInsteadOfLimitExhaustionPercentage() {
		ForeignLimitPredictionEngine engine = new ForeignLimitPredictionEngine(
			Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC)
		);
		var prediction = engine.predict(List.of(
			snapshot("12.0", "80.0", LocalDate.of(2026, 8, 29)),
			snapshot("12.5", "83.3", LocalDate.of(2026, 8, 30)),
			snapshot("13.0", "86.7", LocalDate.of(2026, 8, 31))
		)).orElseThrow();

		assertThat(prediction.baseRate()).isEqualByComparingTo("13.5");
		assertThat(prediction.baseRate()).isLessThan(new BigDecimal("20"));
	}

	private ForeignOwnershipSnapshot snapshot(String ownership, String exhaustion, LocalDate date) {
		return new ForeignOwnershipSnapshot(
			100L, 1_000L, 150L, 50L,
			new BigDecimal(ownership), new BigDecimal(exhaustion), date,
			Instant.parse("2026-08-31T00:00:00Z"), "TEST"
		);
	}
}
