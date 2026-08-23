package com.kmarket.navigator.backend.news.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
		Set<String> leftTokens = tokens(left);
		Set<String> rightTokens = tokens(right);
		if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
			return 0;
		}
		Set<String> intersection = new HashSet<>(leftTokens);
		intersection.retainAll(rightTokens);
		Set<String> union = new HashSet<>(leftTokens);
		union.addAll(rightTokens);
		return (double)intersection.size() / union.size();
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
}
