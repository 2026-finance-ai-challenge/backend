package com.kmarket.navigator.backend.news.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class NewsDuplicateIndexTests {

	private final NewsFingerprint fingerprint = new NewsFingerprint();

	@Test
	void mergesSyndicatedCopyButNotNewFactsOrRewrittenFollowup() {
		var time = Instant.parse("2026-09-01T00:00:00Z");
		var id = UUID.randomUUID();
		var index = new NewsDuplicateIndex(fingerprint);
		String body = java.util.stream.IntStream.range(0, 100)
			.mapToObj(i -> "공급망자료" + i).collect(java.util.stream.Collectors.joining(" "));
		String syndicated = "취재기자 설명 " + body.replace("공급망자료20", "공급망자료20 관계자는")
			.replace("공급망자료50", "공급망자료50 밝혔다");
		index.add(id, fingerprint.profile("삼성전자 공급망 협력 730억 계약 체결", "", body), time,
			"first.example.com", java.util.Set.of("005930"));
		assertThat(index.findBest(fingerprint.profile("삼성전자 공급망 협력 730억원 계약 체결 발표", "", syndicated),
			time.plusSeconds(3600), "second.example.com", java.util.Set.of("005930")).targetClusterId()).isEqualTo(id);
		assertThat(index.findBest(fingerprint.profile("삼성전자 공급망 협력 950억원 계약 체결 발표", "", syndicated),
			time.plusSeconds(3600), "second.example.com", java.util.Set.of("005930")).targetClusterId()).isNull();
		String rewritten = java.util.stream.IntStream.range(0, 100)
			.mapToObj(i -> "공급망자료" + (99 - i)).collect(java.util.stream.Collectors.joining(" "));
		assertThat(index.findBest(fingerprint.profile("삼성전자 공급망 협력 730억원 계약 체결 발표", "", rewritten),
			time.plusSeconds(3600), "second.example.com", java.util.Set.of("005930")).targetClusterId()).isNull();
	}

	@Test
	void keepsDifferentIssuersSeparateDespiteSimilarHeadlines() {
		var index = new NewsDuplicateIndex(fingerprint);
		var time = Instant.parse("2026-09-01T00:00:00Z");
		index.add(UUID.randomUUID(), fingerprint.profile("삼성전자 주가 장중 상승 투자 기대", "시장 투자 기대에 주가 상승 흐름이다."),
			time, "first.example.com", java.util.Set.of("005930"));
		assertThat(index.findBest(fingerprint.profile("SK하이닉스 주가 장중 상승 투자 기대", "시장 투자 기대에 주가 상승 흐름이다."),
			time, "second.example.com", java.util.Set.of("000660")).targetClusterId()).isNull();
	}

	@Test
	void usesBodyEvidenceForSamePublisherReposts() {
		var index = new NewsDuplicateIndex(fingerprint);
		var time = Instant.parse("2026-09-01T00:00:00Z");
		var id = UUID.randomUUID();
		String body = "삼성전자는 한국전력과 협력해 스마트가전 캐시백 시범사업을 운영한다고 밝혔다. "
			+ "사업 참여 고객은 주말과 공휴일에 대상 가전을 사용하면 전력 사용량에 따라 혜택을 받는다. "
			+ "지급된 혜택은 전기요금 차감 또는 환급 방식으로 제공되며 구체적인 신청 방법은 회사 홈페이지에서 확인할 수 있다. "
			+ "회사는 소비자의 전력 사용을 줄이기 위해 추가 서비스도 개발하고 있다고 설명했다. "
			+ "이번 협력은 에너지 사용 시간대를 분산하고 가계 비용 부담을 낮추기 위한 것이다.";
		index.add(id, fingerprint.profile("삼성전자 전기요금 아끼고 캐시백 받으세요", "", body), time,
			"news1.kr", java.util.Set.of("005930"));
		assertThat(index.findBest(fingerprint.profile("삼성전자 스마트가전으로 전기요금 아끼고 캐시백 받으세요", "", body),
			time, "news1.kr", java.util.Set.of("005930")).targetClusterId()).isEqualTo(id);
	}

	@Test
	void exactTitlesRemainIndexedWhenEveryTokenIsFrequent() {
		var index = new NewsDuplicateIndex(fingerprint);
		var time = Instant.parse("2026-09-01T00:00:00Z");
		var profile = fingerprint.profile("삼성전자 차세대 반도체 생산 투자 확대", "");
		for (int i = 0; i < 501; i++) index.add(UUID.randomUUID(), profile, time, "news.example.com");
		assertThat(index.findBest(profile, time, "news.example.com").targetClusterId()).isNotNull();
	}

	@Test
	void mergesExactExcerptFromDifferentPublishersDespiteDifferentHeadlines() {
		UUID clusterId = UUID.randomUUID();
		Instant publishedAt = Instant.parse("2026-08-29T00:00:00Z");
		String excerpt = "정부는 집중호우 피해 복구를 위해 관계 부처 합동 지원 대책을 발표하고 긴급 예산을 투입한다고 밝혔다. 피해 지역 주민에게 임시 주거와 생필품을 제공하고 도로 복구 작업도 즉시 시작할 예정이다.";
		NewsDuplicateIndex index = new NewsDuplicateIndex(fingerprint);
		index.add(
			clusterId,
			fingerprint.profile("정부 집중호우 피해 복구 대책 발표", excerpt),
			publishedAt,
			"first.example.com"
		);

		NewsDuplicateIndex.Match match = index.findBest(
			fingerprint.profile("관계 부처 긴급 예산 투입 결정", excerpt),
			publishedAt.plusSeconds(600),
			"second.example.com"
		);

		assertThat(match.targetClusterId()).isEqualTo(clusterId);
	}

	@Test
	void keepsSamePublisherBoilerplateOutOfUnrelatedStories() {
		Instant publishedAt = Instant.parse("2026-08-29T00:00:00Z");
		String boilerplate = "아래는 위 기사를 구글 번역으로 번역한 영문 기사의 전문입니다 원본 기사 보기와 기자 연락처가 포함됩니다.";
		NewsDuplicateIndex index = new NewsDuplicateIndex(fingerprint);
		index.add(
			UUID.randomUUID(),
			fingerprint.profile("지역 축제 개막 시민 참여 확대", boilerplate),
			publishedAt,
			"same.example.com"
		);

		NewsDuplicateIndex.Match match = index.findBest(
			fingerprint.profile("기업 실적 악화 구조조정 검토", boilerplate),
			publishedAt.plusSeconds(600),
			"www.same.example.com"
		);

		assertThat(match.targetClusterId()).isNull();
	}

	@Test
	void prefersCrossPublisherExactEvidenceWhenScoresAreTied() {
		UUID currentClusterId = UUID.randomUUID();
		UUID crossPublisherClusterId = UUID.randomUUID();
		Instant publishedAt = Instant.parse("2026-08-29T00:00:00Z");
		String sharedExcerpt = "현대글로비스는 화장품과 음반의 해외 항공운송 계약을 수주했으며 신규 고객을 위한 국제 물류 서비스를 확대한다고 밝혔다. 회사는 북미와 유럽 주요 도시로 운송 범위를 단계적으로 넓힐 계획이다.";
		NewsFingerprint.Profile incoming = fingerprint.profile(
			"현대글로비스 화장품 음반 항공운송 계약 수주",
			sharedExcerpt
		);
		NewsDuplicateIndex index = new NewsDuplicateIndex(fingerprint);
		index.add(currentClusterId, incoming, publishedAt, "same.example.com");
		index.add(
			crossPublisherClusterId,
			fingerprint.profile("글로벌 물류 신규 고객 운송 확대", sharedExcerpt),
			publishedAt.minusSeconds(600),
			"other.example.com"
		);

		NewsDuplicateIndex.Match match = index.findBest(
			incoming,
			publishedAt.plusSeconds(600),
			"www.same.example.com"
		);

		assertThat(match.targetClusterId()).isEqualTo(crossPublisherClusterId);
	}

	@Test
	void keepsDifferentPhotoArticlesFromSamePublisherSeparate() {
		Instant publishedAt = Instant.parse("2026-08-29T00:00:00Z");
		NewsDuplicateIndex index = new NewsDuplicateIndex(fingerprint);
		index.add(
			UUID.randomUUID(),
			fingerprint.profile(
				"[mhn포토] 임희정 티는 견고하게",
				"임희정이 대회 첫날 1번 홀에서 티샷을 준비하고 있다"
			),
			publishedAt,
			"mhnse.com"
		);

		NewsDuplicateIndex.Match match = index.findBest(
			fingerprint.profile(
				"[mhn포토] 박결 시즌 첫 우승 향해 날린다",
				"박결이 대회 첫날 1번 홀에서 힘차게 티샷하고 있다"
			),
			publishedAt.plusSeconds(60),
			"www.mhnse.com"
		);

		assertThat(match.targetClusterId()).isNull();
	}

	@Test
	void mergesSamePublisherSameHeadlineWithinOneEdition() {
		UUID clusterId = UUID.randomUUID();
		Instant publishedAt = Instant.parse("2026-08-29T00:00:00Z");
		NewsDuplicateIndex index = new NewsDuplicateIndex(fingerprint);
		index.add(
			clusterId,
			fingerprint.profile(
				"삼전닉스 성장과 주주환원 효과 더 누리는 투자법",
				"삼성전자 실적과 배당 정책을 분석한 첫 번째 기사 요약"
			),
			publishedAt,
			"same.example.com"
		);

		NewsDuplicateIndex.Match match = index.findBest(
			fingerprint.profile(
				"삼전닉스 성장과 주주환원 효과 더 누리는 투자법",
				"동일 기사에 포함된 다른 문단을 노출한 두 번째 기사 요약"
			),
			publishedAt.plusSeconds(60),
			"www.same.example.com"
		);

		assertThat(match.targetClusterId()).isEqualTo(clusterId);
	}
}
