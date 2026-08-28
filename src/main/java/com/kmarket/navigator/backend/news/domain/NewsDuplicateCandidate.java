package com.kmarket.navigator.backend.news.domain;

import java.time.Instant;
import java.util.UUID;

public record NewsDuplicateCandidate(
	UUID articleId,
	UUID clusterId,
	String title,
	String excerpt,
	Instant publishedAt
) {
}
