package com.kmarket.navigator.backend.stock.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.stock.domain.ForeignLimitPrediction;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;

@Component
public class ForeignLimitPredictionEngine {

	private static final String MODEL_VERSION = "foreign-limit-timeseries-v1";
	private static final BigDecimal MAX_UNCERTAINTY = new BigDecimal("1.500000");
	private final Clock clock;

	public ForeignLimitPredictionEngine() {
		this(Clock.systemUTC());
	}

	ForeignLimitPredictionEngine(Clock clock) {
		this.clock = clock;
	}

	public Optional<ForeignLimitPrediction> predict(List<ForeignOwnershipSnapshot> snapshots) {
		List<ForeignOwnershipSnapshot> history = snapshots.stream()
			.filter(snapshot -> snapshot.limitExhaustionRate() != null)
			.sorted(Comparator.comparing(ForeignOwnershipSnapshot::baseDate))
			.toList();
		if (history.size() < 2) {
			return Optional.empty();
		}

		ForeignOwnershipSnapshot first = history.getFirst();
		ForeignOwnershipSnapshot latest = history.getLast();
		long windowDays = Math.max(1, ChronoUnit.DAYS.between(first.baseDate(), latest.baseDate()));
		BigDecimal dailyTrend = latest.limitExhaustionRate()
			.subtract(first.limitExhaustionRate())
			.divide(BigDecimal.valueOf(windowDays), 6, RoundingMode.HALF_UP);
		BigDecimal base = clamp(latest.limitExhaustionRate().add(dailyTrend));
		BigDecimal uncertainty = averageAbsoluteDailyChange(history).min(MAX_UNCERTAINTY);
		BigDecimal confidence = new BigDecimal("0.5500")
			.add(BigDecimal.valueOf(Math.min(history.size(), 30))
				.divide(new BigDecimal("30"), 4, RoundingMode.HALF_UP)
				.multiply(new BigDecimal("0.3500")))
			.setScale(4, RoundingMode.HALF_UP);

		return Optional.of(new ForeignLimitPrediction(
			clamp(base.subtract(uncertainty)),
			base,
			clamp(base.add(uncertainty)),
			history.size(),
			Math.toIntExact(windowDays),
			confidence,
			MODEL_VERSION,
			latest.baseDate(),
			clock.instant(),
			"KRX_FOREIGN_OWNERSHIP_TIMESERIES"
		));
	}

	private BigDecimal averageAbsoluteDailyChange(List<ForeignOwnershipSnapshot> history) {
		BigDecimal sum = BigDecimal.ZERO;
		for (int index = 1; index < history.size(); index++) {
			ForeignOwnershipSnapshot previous = history.get(index - 1);
			ForeignOwnershipSnapshot current = history.get(index);
			long days = Math.max(1, ChronoUnit.DAYS.between(previous.baseDate(), current.baseDate()));
			sum = sum.add(current.limitExhaustionRate()
				.subtract(previous.limitExhaustionRate())
				.abs()
				.divide(BigDecimal.valueOf(days), 6, RoundingMode.HALF_UP));
		}
		return sum.divide(BigDecimal.valueOf(history.size() - 1), 6, RoundingMode.HALF_UP);
	}

	private BigDecimal clamp(BigDecimal value) {
		return value.max(BigDecimal.ZERO)
			.min(new BigDecimal("100"))
			.setScale(6, RoundingMode.HALF_UP);
	}
}
