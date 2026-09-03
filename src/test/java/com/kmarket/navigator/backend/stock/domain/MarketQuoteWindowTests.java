package com.kmarket.navigator.backend.stock.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MarketQuoteWindowTests {

	@Test
	void preservesPreviousSessionAcrossMidnightAndPreopen() {
		for (String timestamp : java.util.List.of("2026-09-03T15:00:00Z", "2026-09-03T23:59:59Z", "2026-09-06T23:59:59Z")) {
			assertThat(MarketQuoteWindow.acceptsRestQuote(Instant.parse(timestamp))).isFalse();
		}
		assertThat(MarketQuoteWindow.latestStartedDate(Instant.parse("2026-09-03T23:59:59Z"))).hasToString("2026-09-03");
		assertThat(MarketQuoteWindow.latestStartedDate(Instant.parse("2026-09-06T23:59:59Z"))).hasToString("2026-09-04");
	}

	@Test
	void admitsOpenAndClosingReconciliationButNeverReplaysOldTicksAsLive() {
		Instant open = Instant.parse("2026-09-04T00:00:00Z");
		assertThat(MarketQuoteWindow.acceptsRestQuote(open)).isTrue();
		assertThat(MarketQuoteWindow.acceptsRestQuote(Instant.parse("2026-09-04T09:00:00Z"))).isTrue();
		assertThat(MarketQuoteWindow.acceptsLiveQuote(open, open)).isTrue();
		assertThat(MarketQuoteWindow.acceptsLiveQuote(open, open.minusSeconds(86400))).isFalse();
		assertThat(MarketQuoteWindow.acceptsLiveQuote(open, null)).isFalse();
		assertThat(MarketQuoteWindow.acceptsLiveQuote(Instant.parse("2026-09-04T07:00:00Z"), Instant.parse("2026-09-04T06:30:00Z"))).isFalse();
	}
}
