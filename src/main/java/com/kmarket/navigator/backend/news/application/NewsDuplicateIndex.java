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
	private final Map<String, List<Entry>> entriesByTitle = new HashMap<>();
	private final Map<String, List<Entry>> entriesByBody = new HashMap<>();

	NewsDuplicateIndex(NewsFingerprint fingerprint) {
		this.fingerprint = fingerprint;
	}

	void add(UUID targetClusterId, NewsFingerprint.Profile profile, Instant publishedAt, String publisher) {
		add(targetClusterId, profile, publishedAt, publisher, Set.of());
	}

	void add(UUID targetClusterId, NewsFingerprint.Profile profile, Instant publishedAt, String publisher, Set<String> stocks) {
		Entry entry = new Entry(targetClusterId, profile, publishedAt, normalizedPublisher(publisher), Set.copyOf(stocks));
		entriesByTitle.computeIfAbsent(profile.normalizedTitle(), ignored -> new ArrayList<>()).add(entry);
		if (!profile.bodyHash().isBlank()) entriesByBody.computeIfAbsent(profile.bodyHash(), ignored -> new ArrayList<>()).add(entry);
		for (String token : profile.indexTokens()) {
			entriesByToken.computeIfAbsent(token, ignored -> new ArrayList<>()).add(entry);
		}
	}

	Match findBest(NewsFingerprint.Profile profile, Instant publishedAt, String publisher) {
		return findBest(profile, publishedAt, publisher, Set.of());
	}

	Match findBest(NewsFingerprint.Profile profile, Instant publishedAt, String publisher, Set<String> stocks) {
		Set<Entry> possible = Collections.newSetFromMap(new IdentityHashMap<>());
		possible.addAll(entriesByTitle.getOrDefault(profile.normalizedTitle(), List.of()));
		possible.addAll(entriesByBody.getOrDefault(profile.bodyHash(), List.of()));
		for (String token : profile.indexTokens()) {
			List<Entry> entries = entriesByToken.getOrDefault(token, List.of());
			if (entries.size() <= MAX_ENTRIES_PER_TOKEN) {
				possible.addAll(entries);
			}
		}
		Entry best = null;
		double bestScore = 0;
		boolean bestCorroborated = false;
		for (Entry candidate : possible) {
			if (!stocks.isEmpty() && !candidate.stocks().isEmpty() && Collections.disjoint(stocks, candidate.stocks())) continue;
			if (fingerprint.conflictingHeadlineNumbers(profile, candidate.profile())) continue;
			NewsFingerprint.DuplicateMatch match = fingerprint.match(
				profile,
				publishedAt,
				candidate.profile(),
				candidate.publishedAt()
			);
			boolean corroborated = corroboratedByDifferentPublisher(
				profile,
				publishedAt,
				normalizedPublisher(publisher),
				candidate
			);
			boolean samePublisher = !candidate.publisher().isBlank()
				&& candidate.publisher().equals(normalizedPublisher(publisher));
			boolean sameBody = match.titleScore() >= 0.55 && fingerprint.sameBody(profile, candidate.profile())
				&& Duration.between(publishedAt, candidate.publishedAt()).abs().compareTo(Duration.ofHours(36)) <= 0;
			boolean duplicate = sameBody || corroborated
				|| (!samePublisher && match.duplicate())
				|| (samePublisher && strictSamePublisherDuplicate(
					profile,
					publishedAt,
					candidate,
					match
					));
			// 양쪽 전문이 있으면 검색 요약의 유사성만으로 서로 다른 후속 기사를 버리지 않는다.
			if (!profile.bodyHash().isBlank() && !candidate.profile().bodyHash().isBlank()) duplicate = sameBody;
			boolean strongerEvidence = corroborated && !bestCorroborated;
			if (duplicate && (best == null
				|| strongerEvidence
				|| (corroborated == bestCorroborated && match.score() > bestScore))) {
				best = candidate;
				bestScore = match.score();
				bestCorroborated = corroborated;
			}
		}
		return new Match(
			best == null ? null : best.targetClusterId(),
			bestScore,
			possible.size(),
			best == null ? Set.of() : best.stocks()
		);
	}

	private boolean strictSamePublisherDuplicate(
		NewsFingerprint.Profile profile,
		Instant publishedAt,
		Entry candidate,
		NewsFingerprint.DuplicateMatch match
	) {
		boolean exactTitleInSameEdition = profile.normalizedTitle().length() >= 12
			&& profile.normalizedTitle().equals(candidate.profile().normalizedTitle())
			&& Duration.between(publishedAt, candidate.publishedAt()).abs()
				.compareTo(Duration.ofHours(12)) <= 0;
		boolean exactExcerpt = profile.normalizedExcerpt().length() >= 80
			&& profile.normalizedExcerpt().equals(candidate.profile().normalizedExcerpt());
		boolean exactSpecificTitle = profile.titleTokens().size() >= 5
			&& profile.normalizedTitle().equals(candidate.profile().normalizedTitle())
			&& match.excerptScore() >= 0.75
			&& Duration.between(publishedAt, candidate.publishedAt()).abs().compareTo(Duration.ofHours(12)) <= 0;
		boolean nearDuplicateInSameEdition = profile.titleTokens().size() >= 4
			&& match.titleScore() >= 0.86
			&& match.excerptScore() >= 0.50
			&& Duration.between(publishedAt, candidate.publishedAt()).abs().compareTo(Duration.ofHours(6)) <= 0;
		return exactTitleInSameEdition || exactExcerpt || exactSpecificTitle || nearDuplicateInSameEdition;
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
		boolean exactSpecificTitle = profile.normalizedTitle().length() >= 12
			&& profile.normalizedTitle().equals(candidate.profile().normalizedTitle());
		return exactExcerpt || exactSpecificTitle;
	}

	private String normalizedPublisher(String publisher) {
		return publisher == null ? "" : publisher.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
	}

	record Match(UUID targetClusterId, double score, int comparisons, Set<String> stocks) {
	}

	private record Entry(
		UUID targetClusterId,
		NewsFingerprint.Profile profile,
		Instant publishedAt,
		String publisher,
		Set<String> stocks
	) {
	}
}
