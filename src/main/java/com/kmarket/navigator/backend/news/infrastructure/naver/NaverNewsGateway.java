package com.kmarket.navigator.backend.news.infrastructure.naver;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.news.application.port.NewsProviderGateway;
import com.kmarket.navigator.backend.news.domain.CollectedNewsArticle;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class NaverNewsGateway implements NewsProviderGateway {

	private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
	private final RestClient restClient;
	private final NaverNewsProperties properties;
	private final ObjectMapper objectMapper;

	NaverNewsGateway(
		@Qualifier("naverNewsRestClient") RestClient restClient,
		NaverNewsProperties properties,
		ObjectMapper objectMapper
	) {
		this.restClient = restClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean configured() {
		return properties.configured();
	}

	@Override
	public List<CollectedNewsArticle> search(String query, int display) {
		if (!configured()) {
			return List.of();
		}
		JsonNode root = restClient.get()
			.uri(uriBuilder -> uriBuilder
				.path("/v1/search/news.json")
				.queryParam("query", query)
				.queryParam("display", Math.max(1, Math.min(display, 100)))
				.queryParam("sort", "date")
				.build())
			.header("X-Naver-Client-Id", properties.getClientId())
			.header("X-Naver-Client-Secret", properties.getClientSecret())
			.exchange((request, response) -> {
				try {
					if (!response.getStatusCode().is2xxSuccessful()) {
						throw new IllegalStateException("Naver News request was rejected");
					}
					byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
					if (body.length > MAX_RESPONSE_BYTES) {
						throw new IllegalStateException("Naver News response exceeds size limit");
					}
					return objectMapper.readTree(body);
				} catch (IOException exception) {
					throw new IllegalStateException("Naver News response could not be read", exception);
				}
			});
		JsonNode items = root.path("items");
		if (!items.isArray()) {
			return List.of();
		}
		List<CollectedNewsArticle> articles = new ArrayList<>();
		for (JsonNode item : items) {
			map(item).ifPresent(articles::add);
		}
		return List.copyOf(articles);
	}

	private java.util.Optional<CollectedNewsArticle> map(JsonNode item) {
		String title = clean(text(item, "title"));
		String excerpt = clean(text(item, "description"));
		String link = text(item, "originallink");
		if (link.isBlank()) {
			link = text(item, "link");
		}
		Instant publishedAt = parsePublishedAt(text(item, "pubDate"));
		if (title.isBlank() || link.isBlank() || publishedAt == null) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new CollectedNewsArticle(
			sha256(link),
			title,
			excerpt,
			link,
			link,
			publisher(link),
			null,
			publishedAt
		));
	}

	private String publisher(String link) {
		try {
			return URI.create(link).getHost();
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private String clean(String value) {
		return Jsoup.parse(value).text().trim();
	}

	private Instant parsePublishedAt(String value) {
		try {
			return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
		} catch (DateTimeParseException exception) {
			return null;
		}
	}

	private String text(JsonNode node, String field) {
		if (!node.has(field) || node.path(field).isNull()) {
			return "";
		}
		String value = node.path(field).stringValue();
		return value == null ? "" : value.trim();
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8))
			);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
		}
	}
}
