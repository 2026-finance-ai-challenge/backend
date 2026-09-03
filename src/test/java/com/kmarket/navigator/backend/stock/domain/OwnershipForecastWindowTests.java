package com.kmarket.navigator.backend.stock.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class OwnershipForecastWindowTests {
	@Test
	void distinguishesPreopenIntradayAndCloseWithoutUsingStaleQuotes() {
		assertWindow("2026-09-02T23:59:59Z", "2026-09-03", "NEXT_SESSION");
		assertWindow("2026-09-03T00:00:00Z", "2026-09-03", "INTRADAY");
		assertWindow("2026-09-03T06:29:59Z", "2026-09-03", "INTRADAY");
		assertWindow("2026-09-03T06:30:00Z", "2026-09-04", "NEXT_SESSION");
		assertWindow("2026-09-03T15:01:00Z", "2026-09-04", "NEXT_SESSION");
	}

	@Test
	void skipsWeekendsAndExchangeHolidays() {
		assertWindow("2026-09-04T07:00:00Z", "2026-09-07", "NEXT_SESSION");
		assertWindow("2026-09-05T02:00:00Z", "2026-09-07", "NEXT_SESSION");
		assertWindow("2026-09-23T07:00:00Z", "2026-09-28", "NEXT_SESSION");
		assertWindow("2026-10-02T07:00:00Z", "2026-10-06", "NEXT_SESSION");
	}

	@Test
	void doesNotInventDatesOutsideTheMaintainedCalendar() {
		assertThat(OwnershipForecastWindow.nextTradingDay(LocalDate.of(2026, 12, 30))).isNull();
		assertThat(OwnershipForecastWindow.at(Instant.parse("2027-01-04T02:00:00Z")).targetDate()).isNull();
	}

	private void assertWindow(String now, String date, String session) {
		var window = OwnershipForecastWindow.at(Instant.parse(now));
		assertThat(window.targetDate()).isEqualTo(LocalDate.parse(date));
		assertThat(window.session()).isEqualTo(session);
	}
}
