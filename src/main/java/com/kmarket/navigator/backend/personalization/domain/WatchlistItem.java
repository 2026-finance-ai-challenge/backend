package com.kmarket.navigator.backend.personalization.domain;

import java.time.Instant;

public record WatchlistItem(SupportedStock stock, Instant addedAt) {
}
