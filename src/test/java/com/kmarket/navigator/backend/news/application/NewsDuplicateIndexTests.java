package com.kmarket.navigator.backend.news.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class NewsDuplicateIndexTests {

	private final NewsFingerprint fingerprint = new NewsFingerprint();

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
}
