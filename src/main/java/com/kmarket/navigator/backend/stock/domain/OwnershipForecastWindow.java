package com.kmarket.navigator.backend.stock.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

public record OwnershipForecastWindow(LocalDate targetDate, String session) {

	private static final Set<LocalDate> HOLIDAYS = Set.of(
		"2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18", "2026-03-02",
		"2026-05-01", "2026-05-05", "2026-05-25", "2026-06-03", "2026-08-17",
		"2026-09-24", "2026-09-25", "2026-10-05", "2026-10-09", "2026-12-25", "2026-12-31"
	).stream().map(LocalDate::parse).collect(java.util.stream.Collectors.toUnmodifiableSet());

	public static OwnershipForecastWindow at(Instant now) {
		var local = now.atZone(ZoneId.of("Asia/Seoul"));
		LocalDate today = local.toLocalDate();
		if (tradingDay(today) && local.toLocalTime().isBefore(LocalTime.of(15, 30))) {
			return new OwnershipForecastWindow(today,
				local.toLocalTime().isBefore(LocalTime.of(9, 0)) ? "NEXT_SESSION" : "INTRADAY");
		}
		return new OwnershipForecastWindow(nextTradingDay(today), "NEXT_SESSION");
	}

	public static LocalDate nextTradingDay(LocalDate date) {
		if (date == null || date.getYear() != 2026) return null;
		LocalDate next = date.plusDays(1);
		while (next.getYear() == 2026 && !tradingDay(next)) next = next.plusDays(1);
		// 공표된 거래 달력 밖의 날짜는 평일만으로 추정하지 않는다.
		return next.getYear() == 2026 ? next : null;
	}

	private static boolean tradingDay(LocalDate date) {
		return date.getYear() == 2026 && date.getDayOfWeek().getValue() <= 5 && !HOLIDAYS.contains(date);
	}
}
