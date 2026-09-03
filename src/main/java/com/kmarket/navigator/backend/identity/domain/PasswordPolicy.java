package com.kmarket.navigator.backend.identity.domain;

public final class PasswordPolicy {
	public static final String PATTERN = "^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[\\p{P}\\p{S}])[^\\s\\p{C}]{8,128}$";
	private static final java.util.regex.Pattern COMPILED = java.util.regex.Pattern.compile(PATTERN,
		java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

	private PasswordPolicy() { }

	public static boolean isValid(String value) {
		return value != null && COMPILED.matcher(value).matches();
	}
}
