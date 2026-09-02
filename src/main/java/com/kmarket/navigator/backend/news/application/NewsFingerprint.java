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
		return profile(title, excerpt, "");
	}

	public Profile profile(String title, String excerpt, String body) {
		String normalizedTitle = normalize(title);
		String normalizedExcerpt = normalize(excerpt);
		String normalizedBody = normalize(body);
		return new Profile(
			normalizedTitle,
			normalizedExcerpt,
			tokens(normalizedTitle),
			tokens(normalizedExcerpt),
			normalizedBody.length() >= 200 ? sha256(normalizedBody) : "",
			bodySketch(normalizedBody),
			bodyTokenSketch(normalizedBody),
			headlineNumbers(title == null ? "" : title.replace(",", ""))
		);
	}

	private Set<Long> bodySketch(String body) {
		if (body.length() < 200) return Set.of();
		String[] words = body.split(" ");
		var sketch = new java.util.TreeSet<Long>();
		// 원문 전체 대신 제한된 5어절 지문만 유지한다. 단어 순서가 다른 후속 보도는 분리한다.
		for (int i = 0; i + 4 < words.length; i++) {
			String shingle = String.join(" ", java.util.Arrays.copyOfRange(words, i, i + 5));
			long value = Long.parseUnsignedLong(sha256(shingle).substring(0, 16), 16);
			sketch.add(value);
			if (sketch.size() > 256) sketch.pollLast();
		}
		return Set.copyOf(sketch);
	}

	boolean sameBody(Profile left, Profile right) {
		if (left.bodyHash().isBlank() || right.bodyHash().isBlank()) return false;
		if (left.bodyHash().equals(right.bodyHash())) return true;
		if (Math.min(left.bodySketch().size(), right.bodySketch().size()) < 20) return false;
		double phrases = sketchSimilarity(left.bodySketch(), right.bodySketch());
		// 인용부호·기자 문구만 바꾼 보도자료 재전송은 어절 순서와 어휘가 모두 비슷해야 묶는다.
		return phrases >= 0.90 || (phrases >= 0.70
			&& sketchSimilarity(left.bodyTokenSketch(), right.bodyTokenSketch()) >= 0.85);
	}

	private Set<Long> bodyTokenSketch(String body) {
		if (body.length() < 200) return Set.of();
		var sketch = new java.util.TreeSet<Long>();
		for (String token : tokens(body)) {
			sketch.add(Long.parseUnsignedLong(sha256(token).substring(0, 16), 16));
			if (sketch.size() > 256) sketch.pollLast();
		}
		return Set.copyOf(sketch);
	}

	private double sketchSimilarity(Set<Long> left, Set<Long> right) {
		if (left.isEmpty() || right.isEmpty()) return 0;
		var common = new HashSet<>(left);
		common.retainAll(right);
		return 2.0 * common.size() / (left.size() + right.size());
	}

	boolean conflictingHeadlineNumbers(Profile left, Profile right) {
		var leftNumbers = left.numericFacts();
		var rightNumbers = right.numericFacts();
		return !leftNumbers.isEmpty() && !rightNumbers.isEmpty() && !leftNumbers.equals(rightNumbers);
	}

	private Set<String> headlineNumbers(String title) {
		return Pattern.compile("[0-9]+(?:\\.[0-9]+)?(?:\\s*[조억만천백십](?:\\s*[0-9]+(?:\\.[0-9]+)?)?)*").matcher(title).results()
			.map(java.util.regex.MatchResult::group).map(this::numericValue).collect(Collectors.toSet());
	}

	private String numericValue(String value) {
		var parts = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([조억만천백십]?)").matcher(value.replaceAll("\\s", ""));
		var total = java.math.BigDecimal.ZERO;
		var group = java.math.BigDecimal.ZERO;
		while (parts.find()) {
			var number = new java.math.BigDecimal(parts.group(1));
			long unit = switch (parts.group(2)) {
				case "조" -> 1_000_000_000_000L; case "억" -> 100_000_000L; case "만" -> 10_000L;
				case "천" -> 1_000L; case "백" -> 100L; case "십" -> 10L; default -> 1L;
			};
			if (unit >= 10_000) {
				total = total.add(group.add(number).multiply(java.math.BigDecimal.valueOf(unit)));
				group = java.math.BigDecimal.ZERO;
			} else group = group.add(number.multiply(java.math.BigDecimal.valueOf(unit)));
		}
		return total.add(group).stripTrailingZeros().toPlainString();
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
		Set<String> excerptTokens,
		String bodyHash,
		Set<Long> bodySketch,
		Set<Long> bodyTokenSketch,
		Set<String> numericFacts
	) {
		public Profile {
			titleTokens = Set.copyOf(titleTokens);
			excerptTokens = Set.copyOf(excerptTokens);
			bodySketch = Set.copyOf(bodySketch);
			bodyTokenSketch = Set.copyOf(bodyTokenSketch);
			numericFacts = Set.copyOf(numericFacts);
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
