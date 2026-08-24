package com.kmarket.navigator.backend.disclosure.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class DisclosureTitlePolicy {

	public static final String CONTENT_KIND = "DISCLOSURE_TITLE";
	public static final String SOURCE_LOCALE = "ko";
	public static final String TARGET_LOCALE = "en";
	public static final String TRANSLATION_VERSION = "codex-disclosure-title-v1";
	public static final String MODEL_ID = "codex-reviewed-catalog";
	public static final String PROMPT_VERSION = "disclosure-title-catalog-v1";

	private DisclosureTitlePolicy() {
	}

	public static String normalize(String title) {
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("Disclosure title must not be blank");
		}
		return title.strip().replaceAll("\\s+", " ");
	}

	public static String sourceHash(String title) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(normalize(title).getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
