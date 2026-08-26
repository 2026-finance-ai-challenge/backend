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
	void calculatesTokenSimilarityAfterNormalization() {
		double similarity = fingerprint.similarity(
			"삼성전자, 반도체 투자 확대 발표",
			"삼성전자 반도체 투자 확대"
		);

		assertThat(similarity).isGreaterThan(0.95);
		assertThat(fingerprint.similarity("", "삼성전자")).isZero();
	}

	@Test
	void detectsSyndicatedArticleWithPublisherWordingAdded() {
		double similarity = fingerprint.similarity(
			"삼성전자 반도체 투자 확대 발표 평택 생산라인 증설",
			"단독 삼성전자 반도체 투자 확대 발표 평택 생산라인 증설 업계 최초"
		);

		assertThat(similarity).isGreaterThanOrEqualTo(0.82);
	}

	@Test
	void keepsShortArticlesOnStrictJaccardToAvoidFalseClusters() {
		double similarity = fingerprint.similarity(
			"삼성전자 실적 발표",
			"삼성전자 실적 전망"
		);

		assertThat(similarity).isLessThan(0.82);
	}
}
