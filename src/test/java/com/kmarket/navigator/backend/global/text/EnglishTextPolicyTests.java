package com.kmarket.navigator.backend.global.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class EnglishTextPolicyTests {

	@Test
	void acceptsEnglishTitle() {
		assertThat(EnglishTextPolicy.isValid("Mothers Pharmaceutical Files for KOSDAQ Review"))
			.isTrue();
	}

	@Test
	void rejectsBlankOrHangulTitle() {
		assertThat(EnglishTextPolicy.isValid(" ")).isFalse();
		assertThat(EnglishTextPolicy.isValid("마더스제약 Files for KOSDAQ Review")).isFalse();
		assertThat(EnglishTextPolicy.isValid("Raises 344 eok won in funding")).isFalse();
		assertThatIllegalArgumentException()
			.isThrownBy(() -> EnglishTextPolicy.requireValid("English 제목"));
	}
}
