package com.kmarket.navigator.backend.news.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;

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

	@Test
	void detectsSameStoryAcrossPublishersWhenTitlesDiffer() {
		var left = fingerprint.profile(
			"'네팔·중국 대홍수' 사망자 584명…실종자 2,500명 육박",
			"네팔과 중국에서 발생한 대규모 홍수로 사망자가 584명으로 늘었고 실종자가 2500명에 육박했다."
		);
		var right = fingerprint.profile(
			"네팔 대홍수 사망자 584명…실종자 2천500명 육박",
			"대홍수 피해가 이어지면서 사망자 584명과 실종자 약 2500명이 집계됐다."
		);

		var match = fingerprint.match(
			left,
			Instant.parse("2026-08-28T22:00:00Z"),
			right,
			Instant.parse("2026-08-28T22:30:00Z")
		);

		assertThat(match.duplicate()).isTrue();
		assertThat(match.titleScore()).isGreaterThanOrEqualTo(0.72);
	}

	@Test
	void doesNotMergeUnrelatedStoriesThatSharePublisherBoilerplate() {
		String boilerplate = "아래는 위 기사를 번역한 영문 기사입니다 원본 기사 보기와 기자 연락처가 포함됩니다";
		var left = fingerprint.profile("지역 축제 개막 시민 참여 확대", boilerplate);
		var right = fingerprint.profile("기업 실적 악화 구조조정 검토", boilerplate);

		var match = fingerprint.match(
			left,
			Instant.parse("2026-08-28T22:00:00Z"),
			right,
			Instant.parse("2026-08-28T22:05:00Z")
		);

		assertThat(match.duplicate()).isFalse();
	}

	@Test
	void doesNotMergeRecurringDailyHeadlineWithoutExcerptAgreement() {
		var left = fingerprint.profile("코스피 하락 출발", "첫 거래일 외국인 매도세로 지수가 하락했다");
		var right = fingerprint.profile("코스피 하락 출발", "다음 거래일 반도체 약세로 지수가 하락했다");

		var match = fingerprint.match(
			left,
			Instant.parse("2026-08-27T00:00:00Z"),
			right,
			Instant.parse("2026-08-28T00:00:00Z")
		);

		assertThat(match.duplicate()).isFalse();
	}
}
