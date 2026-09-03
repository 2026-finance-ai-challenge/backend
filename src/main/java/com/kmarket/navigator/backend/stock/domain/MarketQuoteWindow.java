package com.kmarket.navigator.backend.stock.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

public final class MarketQuoteWindow {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
	private MarketQuoteWindow() { }

	public static boolean acceptsRestQuote(Instant now) {
		var local = now.atZone(KOREA);
		return local.getDayOfWeek().getValue() <= 5
			&& !local.toLocalTime().isBefore(LocalTime.of(9, 0))
			&& local.toLocalTime().isBefore(LocalTime.of(18, 1));
	}

	public static boolean acceptsLiveQuote(Instant now, Instant sampleTime) {
		var local = now.atZone(KOREA);
		return acceptsRestQuote(now) && local.toLocalTime().isBefore(LocalTime.of(15, 31))
			&& sampleTime != null && sampleTime.atZone(KOREA).toLocalDate().equals(local.toLocalDate())
			&& !sampleTime.isAfter(now.plusSeconds(5)) && sampleTime.isAfter(now.minusSeconds(120));
	}

	public static LocalDate latestStartedDate(Instant now) {
		var local = now.atZone(KOREA);
		LocalDate date = local.toLocalTime().isBefore(LocalTime.of(9, 0)) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
		while (date.getDayOfWeek().getValue() > 5) date = date.minusDays(1);
		return date;
	}
}
