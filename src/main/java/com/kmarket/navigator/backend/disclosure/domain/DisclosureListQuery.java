package com.kmarket.navigator.backend.disclosure.domain;

import java.time.LocalDate;
import java.util.Set;

public record DisclosureListQuery(
	String query,
	String stockCode,
	LocalDate from,
	LocalDate to,
	Set<DisclosureType> types,
	Boolean correction,
	DisclosureCursor cursor,
	int limit
) {
	public DisclosureListQuery(
		String stockCode,
		LocalDate from,
		LocalDate to,
		Set<DisclosureType> types,
		DisclosureCursor cursor,
		int limit
	) {
		this(null, stockCode, from, to, types, null, cursor, limit);
	}
}
