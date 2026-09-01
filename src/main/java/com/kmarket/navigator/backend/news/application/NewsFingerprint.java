package com.kmarket.navigator.backend.news.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class NewsFingerprint {

	private static final Pattern NON_WORD = Pattern.compile("[^0-9a-z가-힣 ]+");
	private static final Pattern SPACE = Pattern.compile("\\s+");
	private static final Set<String> TRACKING_PARAMETERS = Set.of(
		"utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "gclid"
	);

	public String normalize(String value) {
		String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
		return SPACE.matcher(NON_WORD.matcher(lower).replaceAll(" ")).replaceAll(" ").trim();
	}

	public String canonicalizeUrl(String rawUrl) {
		try {
			URI uri = new URI(rawUrl).normalize();
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null) {
				throw new IllegalArgumentException("unsupported news URL");
			}
			String query = cleanQuery(uri.getRawQuery());
			return new URI(
				scheme,
				uri.getUserInfo(),
				uri.getHost().toLowerCase(Locale.ROOT),
				uri.getPort(),
				uri.getPath(),
				query,
				null
			).toASCIIString();
		}
		catch (URISyntaxException | IllegalArgumentException exception) {
			throw new IllegalArgumentException("invalid news URL", exception);
		}
	}

	public String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
			);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
		}
	}

	public double similarity(String left, String right) {
		return tokenSimilarity(tokens(left), tokens(right));
	}

	public Profile profile(String title, String excerpt) {
		String normalizedTitle = normalize(title);
		String normalizedExcerpt = normalize(excerpt);
		return new Profile(
			normalizedTitle,
			normalizedExcerpt,
			tokens(normalizedTitle),
			tokens(normalizedExcerpt)
		);
	}

	public DuplicateMatch match(Profile left, Instant leftPublishedAt, Profile right, Instant rightPublishedAt) {
		Duration distance = Duration.between(leftPublishedAt, rightPublishedAt).abs();
		if (distance.compareTo(Duration.ofHours(72)) > 0) {
			return DuplicateMatch.notDuplicate();
		}
		double title = tokenSimilarity(left.titleTokens(), right.titleTokens());
		double excerpt = meaningfulExcerpt(left, right)
			? tokenSimilarity(left.excerptTokens(), right.excerptTokens())
			: 0;
		Set<String> leftCombined = combined(left);
		Set<String> rightCombined = combined(right);
		double combined = tokenSimilarity(leftCombined, rightCombined);
		double score = Math.max(title, Math.max(excerpt, combined));
		boolean shortTitle = Math.min(left.titleTokens().size(), right.titleTokens().size()) < 4;
		boolean exactTitle = !left.normalizedTitle().isBlank()
			&& left.normalizedTitle().equals(right.normalizedTitle());
		boolean duplicate;
		if (distance.compareTo(Duration.ofHours(12)) <= 0) {
			duplicate = exactTitle
				|| (!shortTitle && title >= 0.66)
				|| (title >= 0.58 && excerpt >= 0.58)
				|| (title >= 0.35 && excerpt >= 0.72)
				|| (title >= 0.35 && combined >= 0.82);
		} else if (distance.compareTo(Duration.ofHours(36)) <= 0) {
			duplicate = (!shortTitle && title >= 0.82 && excerpt >= 0.30)
				|| (title >= 0.65 && excerpt >= 0.65)
				|| (title >= 0.50 && excerpt >= 0.78)
				|| (title >= 0.40 && combined >= 0.88);
		} else {
			duplicate = title >= 0.90 && excerpt >= 0.70;
		}
		return new DuplicateMatch(duplicate, score, title, excerpt, combined);
	}

	private double tokenSimilarity(Set<String> leftTokens, Set<String> rightTokens) {
		if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
			return 0;
		}
		Set<String> intersection = new HashSet<>(leftTokens);
		intersection.retainAll(rightTokens);
		Set<String> union = new HashSet<>(leftTokens);
		union.addAll(rightTokens);
		double jaccard = (double)intersection.size() / union.size();
		if (Math.min(leftTokens.size(), rightTokens.size()) < 4) {
			return jaccard;
		}
		double containment = (double)intersection.size()
			/ Math.min(leftTokens.size(), rightTokens.size());
		double dice = (2.0 * intersection.size()) / (leftTokens.size() + rightTokens.size());
		// 언론사가 제목에 보충 문구를 덧붙인 재전송 기사도 같은 뉴스로 묶는다.
		return Math.max(jaccard, (containment * 0.65) + (dice * 0.35));
	}

	private boolean meaningfulExcerpt(Profile left, Profile right) {
		return left.normalizedExcerpt().length() >= 40 && right.normalizedExcerpt().length() >= 40;
	}

	private Set<String> combined(Profile profile) {
		Set<String> combined = new HashSet<>(profile.titleTokens());
		combined.addAll(profile.excerptTokens());
		return combined;
	}

	private Set<String> tokens(String value) {
		return Arrays.stream(normalize(value).split(" "))
			.filter(token -> token.length() >= 2)
			.collect(Collectors.toSet());
	}

	private String cleanQuery(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}
		String cleaned = Arrays.stream(query.split("&"))
			.filter(part -> {
				String name = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
				return !TRACKING_PARAMETERS.contains(name);
			})
			.sorted()
			.collect(Collectors.joining("&"));
		return cleaned.isBlank() ? null : cleaned;
	}

	public record Profile(
		String normalizedTitle,
		String normalizedExcerpt,
		Set<String> titleTokens,
		Set<String> excerptTokens
	) {
		public Profile {
			titleTokens = Set.copyOf(titleTokens);
			excerptTokens = Set.copyOf(excerptTokens);
		}

		public Set<String> indexTokens() {
			Set<String> result = new HashSet<>(titleTokens);
			excerptTokens.stream().filter(token -> token.length() >= 3).forEach(result::add);
			return Set.copyOf(result);
		}
	}

	public record DuplicateMatch(
		boolean duplicate,
		double score,
		double titleScore,
		double excerptScore,
		double combinedScore
	) {
		private static DuplicateMatch notDuplicate() {
			return new DuplicateMatch(false, 0, 0, 0, 0);
		}
	}
}
