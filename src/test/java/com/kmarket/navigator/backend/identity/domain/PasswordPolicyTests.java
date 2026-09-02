package com.kmarket.navigator.backend.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PasswordPolicyTests {
	@Test
	void acceptsEightCharactersWithSymbolWithoutUppercaseOrDigitRequirement() {
		assertThat(PasswordPolicy.isValid("abcdefg!")).isTrue();
		assertThat(PasswordPolicy.isValid("1234567!")).isTrue();
		assertThat(PasswordPolicy.isValid("한글비밀번호입력!")).isTrue();
		assertThat(PasswordPolicy.isValid("a".repeat(127) + "!")).isTrue();
	}

	@Test
	void rejectsShortMissingSymbolWhitespaceControlAndOversizedPasswords() {
		for (String value : java.util.List.of("abcdef!", "abcdefgh", "한글비밀번호입력값", "abcdefg ",
			"abcdefg!\n", "abcdef!\u0000", "a".repeat(128) + "!")) assertThat(PasswordPolicy.isValid(value)).isFalse();
		assertThat(PasswordPolicy.isValid(null)).isFalse();
	}
}
