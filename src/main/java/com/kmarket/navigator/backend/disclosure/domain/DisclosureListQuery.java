package com.kmarket.navigator.backend.disclosure.domain;

import java.time.LocalDate;
import java.util.Set;

public record DisclosureListQuery(
	String stockCode,
	LocalDate from,
	LocalDate to,
	Set<DisclosureType> types,
	DisclosureCursor cursor,
	int limit
) {
}
