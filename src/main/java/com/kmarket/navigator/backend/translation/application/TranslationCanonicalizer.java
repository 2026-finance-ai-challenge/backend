package com.kmarket.navigator.backend.translation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class TranslationCanonicalizer {

	private final ObjectMapper objectMapper;

	public TranslationCanonicalizer(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public Source news(String title, List<String> paragraphs, String contentAvailability) {
		ObjectNode root = objectMapper.createObjectNode();
		// AI 서비스의 키 정렬 JSON 계약과 같은 순서를 유지한다.
		root.put("content_availability", contentAvailability);
		ArrayNode values = root.putArray("paragraphs");
		paragraphs.forEach(values::add);
		root.put("title", title);
		return source(objectMapper.writeValueAsString(root));
	}

	public Source disclosureSection(String heading, String text, String tableDataJson) {
		ObjectNode root = objectMapper.createObjectNode();
		putNullable(root, "heading", heading);
		putNullable(root, "table_data_json", tableDataJson);
		putNullable(root, "text", text);
		return source(objectMapper.writeValueAsString(root));
	}

	private Source source(String canonical) {
		return new Source(canonical, sha256(canonical));
	}

	private static void putNullable(ObjectNode root, String field, String value) {
		if (value == null) {
			root.putNull(field);
		}
		else {
			root.put(field, value);
		}
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
			);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
		}
	}

	public record Source(String canonical, String hash) {
	}
}
