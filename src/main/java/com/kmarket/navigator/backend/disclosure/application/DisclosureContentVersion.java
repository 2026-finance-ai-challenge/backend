package com.kmarket.navigator.backend.disclosure.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;

public final class DisclosureContentVersion {

	private DisclosureContentVersion() {
	}

	public static String calculate(DisclosureDetail detail) {
		String versions = detail.documents().stream()
			.sorted(java.util.Comparator.comparing(document -> document.id().toString()))
			.map(document -> document.id() + ":" + document.version() + ":" + document.contentHash())
			.collect(java.util.stream.Collectors.joining("|"));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(versions.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
		}
	}
}
