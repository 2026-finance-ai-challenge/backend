package com.kmarket.navigator.backend.personalization.domain;

import java.time.Instant;

public record RecentlyViewedItem(
	RecentItemType itemType,
	String referenceId,
	String stockCode,
	Instant viewedAt
) {
}
