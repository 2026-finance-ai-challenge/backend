package com.kmarket.navigator.backend.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PasswordPolicyTests {
	@Test
	void acceptsLettersNumbersAndSymbolsWithoutUppercaseRequirement() {
		assertThat(PasswordPolicy.isValid("abcdef1!")).isTrue();
		assertThat(PasswordPolicy.isValid("ABCDEF1!")).isTrue();
		assertThat(PasswordPolicy.isValid("한글비밀번호a1!")).isTrue();
		assertThat(PasswordPolicy.isValid("a".repeat(126) + "1!")).isTrue();
	}

	@Test
	void rejectsShortMissingSymbolWhitespaceControlAndOversizedPasswords() {
		for (String value : java.util.List.of("abcdefg!", "1234567!", "한글비밀번호입력!", "abcdef!", "abcdefgh", "한글비밀번호입력값", "abcdefg ",
			"abcdefg!\n", "abcdef!\u0000", "a".repeat(128) + "!")) assertThat(PasswordPolicy.isValid(value)).isFalse();
		assertThat(PasswordPolicy.isValid(null)).isFalse();
	}
}
