package com.kmarket.navigator.backend.news.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class NewsDuplicateIndex {

	private static final int MAX_ENTRIES_PER_TOKEN = 500;
	private final NewsFingerprint fingerprint;
	private final Map<String, List<Entry>> entriesByToken = new HashMap<>();

	NewsDuplicateIndex(NewsFingerprint fingerprint) {
		this.fingerprint = fingerprint;
	}

	void add(UUID targetClusterId, NewsFingerprint.Profile profile, Instant publishedAt, String publisher) {
		Entry entry = new Entry(targetClusterId, profile, publishedAt, normalizedPublisher(publisher));
		for (String token : profile.indexTokens()) {
			entriesByToken.computeIfAbsent(token, ignored -> new ArrayList<>()).add(entry);
		}
	}

	Match findBest(NewsFingerprint.Profile profile, Instant publishedAt, String publisher) {
		Set<Entry> possible = Collections.newSetFromMap(new IdentityHashMap<>());
		for (String token : profile.indexTokens()) {
			List<Entry> entries = entriesByToken.getOrDefault(token, List.of());
			if (entries.size() <= MAX_ENTRIES_PER_TOKEN) {
				possible.addAll(entries);
			}
		}
		Entry best = null;
		double bestScore = 0;
		for (Entry candidate : possible) {
			NewsFingerprint.DuplicateMatch match = fingerprint.match(
				profile,
				publishedAt,
				candidate.profile(),
				candidate.publishedAt()
			);
			boolean duplicate = match.duplicate() || corroboratedByDifferentPublisher(
				profile,
				publishedAt,
				normalizedPublisher(publisher),
				candidate
			);
			if (duplicate && match.score() > bestScore) {
				best = candidate;
				bestScore = match.score();
			}
		}
		return new Match(
			best == null ? null : best.targetClusterId(),
			bestScore,
			possible.size()
		);
	}

	private boolean corroboratedByDifferentPublisher(
		NewsFingerprint.Profile profile,
		Instant publishedAt,
		String publisher,
		Entry candidate
	) {
		if (publisher.isBlank() || candidate.publisher().isBlank()
			|| publisher.equals(candidate.publisher())
			|| Duration.between(publishedAt, candidate.publishedAt()).abs().compareTo(Duration.ofHours(36)) > 0) {
			return false;
		}
		boolean exactExcerpt = profile.normalizedExcerpt().length() >= 80
			&& profile.normalizedExcerpt().equals(candidate.profile().normalizedExcerpt());
		boolean exactSpecificTitle = Math.min(
			profile.titleTokens().size(),
			candidate.profile().titleTokens().size()
		) >= 5 && profile.normalizedTitle().equals(candidate.profile().normalizedTitle());
		return exactExcerpt || exactSpecificTitle;
	}

	private String normalizedPublisher(String publisher) {
		return publisher == null ? "" : publisher.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
	}

	record Match(UUID targetClusterId, double score, int comparisons) {
	}

	private record Entry(
		UUID targetClusterId,
		NewsFingerprint.Profile profile,
		Instant publishedAt,
		String publisher
	) {
	}
}
