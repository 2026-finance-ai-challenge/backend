package com.kmarket.navigator.backend.global.text;

import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

public final class EnglishTextPolicy {

	private static final Pattern HANGUL = Pattern.compile("[가-힣ㄱ-ㅎㅏ-ㅣ]");
	private static final Pattern KOREAN_CURRENCY_ROMANIZATION = Pattern.compile(
		"\\b(?:eok|jo)(?:[ -]?won)?\\b|\\bman[ -]?won\\b",
		Pattern.CASE_INSENSITIVE
	);

	private EnglishTextPolicy() {
	}

	public static boolean isValid(String value) {
		return value != null
			&& !value.isBlank()
			&& !HANGUL.matcher(value).find()
			&& !KOREAN_CURRENCY_ROMANIZATION.matcher(value).find();
	}

	public static String requireValid(String value) {
		if (!isValid(value)) {
			throw new IllegalArgumentException("English text must be non-blank and contain no Hangul");
		}
		return value;
	}

	public static void requireAllTextValid(JsonNode value) {
		if (value == null || value.isNull()) {
			return;
		}
		if (value.isString()) {
			String text = value.stringValue();
			if (!text.isBlank() && (HANGUL.matcher(text).find()
				|| KOREAN_CURRENCY_ROMANIZATION.matcher(text).find())) {
				throw new IllegalArgumentException("English payload contains Korean text or currency units");
			}
			return;
		}
		value.forEach(EnglishTextPolicy::requireAllTextValid);
	}
}
