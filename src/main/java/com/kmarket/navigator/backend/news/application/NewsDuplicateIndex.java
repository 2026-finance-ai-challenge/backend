package com.kmarket.navigator.backend.news.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
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

	void add(UUID targetClusterId, NewsFingerprint.Profile profile, Instant publishedAt) {
		Entry entry = new Entry(targetClusterId, profile, publishedAt);
		for (String token : profile.indexTokens()) {
			entriesByToken.computeIfAbsent(token, ignored -> new ArrayList<>()).add(entry);
		}
	}

	Match findBest(NewsFingerprint.Profile profile, Instant publishedAt) {
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
			if (match.duplicate() && match.score() > bestScore) {
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

	record Match(UUID targetClusterId, double score, int comparisons) {
	}

	private record Entry(
		UUID targetClusterId,
		NewsFingerprint.Profile profile,
		Instant publishedAt
	) {
	}
}
