package com.kmarket.navigator.backend.news.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class NewsFingerprintTests {

	private final NewsFingerprint fingerprint = new NewsFingerprint();

	@Test
	void canonicalizesUrlAndRemovesOnlyTrackingParameters() {
		String canonical = fingerprint.canonicalizeUrl(
			"HTTPS://News.Example.com/article/1?utm_source=naver&b=2&a=1#section"
		);

		assertThat(canonical).isEqualTo("https://news.example.com/article/1?a=1&b=2");
	}

	@Test
	void rejectsNonHttpNewsUrls() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> fingerprint.canonicalizeUrl("file:///etc/passwd"));
	}

	@Test
	void calculatesTokenJaccardSimilarityAfterNormalization() {
		double similarity = fingerprint.similarity(
			"삼성전자, 반도체 투자 확대 발표",
			"삼성전자 반도체 투자 확대"
		);

		assertThat(similarity).isEqualTo(0.8);
		assertThat(fingerprint.similarity("", "삼성전자")).isZero();
	}
}
