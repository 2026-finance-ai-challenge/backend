package com.kmarket.navigator.backend.news.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.news.domain.NewsStockMapping;

class NewsStockMatcherTests {

	private final NewsStockMatcher matcher = new NewsStockMatcher();

	@Test
	void doesNotMatchShortEnglishNameInsideAnotherWordOrCompany() {
		var mappings = List.of(
			mapping("034730", "SK", "SK Inc."),
			mapping("000660", "SK하이닉스", "SK hynix Inc.")
		);

		assertThat(matcher.match("시장 리스크 관리가 중요하다", mappings)).isEmpty();
		assertThat(matcher.match("SK하이닉스가 HBM 투자를 확대했다", mappings))
			.containsOnlyKeys("000660");
	}

	@Test
	void matchesExplicitGroupNameWithoutMatchingSubsidiaryPrefix() {
		var mappings = List.of(mapping("034730", "SK", "SK Inc."));

		assertThat(matcher.match("SK그룹이 신규 투자 계획을 발표했다", mappings))
			.containsOnlyKeys("034730");
		assertThat(matcher.match("SK이노베이션이 합병을 발표했다", mappings)).isEmpty();
	}

	@Test
	void keepsOnlyLongestOverlappingKoreanCompanyName() {
		var mappings = List.of(
			mapping("000150", "두산", "DOOSAN CO.,LTD"),
			mapping("034020", "두산에너빌리티", "Doosan Enerbility Co., Ltd")
		);

		assertThat(matcher.match("두산에너빌리티가 원전 계약을 수주했다", mappings))
			.containsOnlyKeys("034020");
	}

	@Test
	void linksEveryExplicitlyMentionedSupportedCompany() {
		var mappings = List.of(
			mapping("005930", "삼성전자", "Samsung Electronics"),
			mapping("000660", "SK하이닉스", "SK hynix")
		);

		assertThat(matcher.match("삼성전자와 SK하이닉스가 HBM 공급을 확대한다", mappings))
			.containsOnlyKeys("005930", "000660");
	}

	@Test
	void rejectsAmbiguousShortEnglishNameFoundOnlyInSportsExcerpt() {
		var mappings = List.of(mapping("034730", "SK", "SK Inc."));

		assertThat(matcher.matchArticle(
			"NORWAY SOCCER",
			"Football match between SK Brann and PAOK at Brann Stadium",
			mappings
		)).isEmpty();
	}

	@Test
	void acceptsAmbiguousShortNameInArticleTitle() {
		var mappings = List.of(mapping("034730", "SK", "SK Inc."));

		assertThat(matcher.matchArticle(
			"SK, 반도체 투자 확대 검토",
			"그룹은 장기 성장 계획을 공개했다",
			mappings
		)).containsOnlyKeys("034730");
	}

	@Test
	void rejectsAmbiguousCompanyNameInSportsTitle() {
		var mappings = List.of(
			mapping("003550", "LG", "LG Corp."),
			mapping("000150", "두산", "DOOSAN CO.,LTD")
		);

		assertThat(matcher.matchArticle(
			"고우석 LG 복귀 현실이 되나, 지명할당 후 FA 선택",
			"프로야구 선수의 다음 팀 결정이 남았다.",
			mappings
		)).isEmpty();
		assertThat(matcher.matchArticle(
			"다승왕은 나중에, 두산의 새 보물 선발",
			"신인 투수가 시즌 첫 경기에 나선다.",
			mappings
		)).isEmpty();
	}

	@Test
	void rejectsUnambiguousCompanyNameInSportsArticle() {
		var mappings = List.of(mapping(
			"012330", "현대모비스", "HYUNDAI MOBIS CO.,LTD"
		));

		assertThat(matcher.matchArticle(
			"현대모비스 여자양궁단 공식 SNS 개설",
			"소속 선수와 대회 소식을 전하는 채널을 열었다.",
			mappings
		)).isEmpty();
	}

	@Test
	void doesNotTreatGyeonggiProvinceAsSportsContext() {
		var mappings = List.of(mapping("005930", "삼성전자", "Samsung Electronics"));

		assertThat(matcher.matchArticle(
			"경기도 산업단지 투자 확대",
			"삼성전자는 신규 생산라인 구축 계획을 발표했다.",
			mappings
		)).containsOnlyKeys("005930");
	}

	@Test
	void acceptsAmbiguousCompanyNameWithFinancialEvidence() {
		var mappings = List.of(mapping("003550", "LG", "LG Corp."));

		assertThat(matcher.matchArticle(
			"LG, 2분기 영업이익 증가",
			"자회사 실적 개선으로 연결 영업이익이 늘었다.",
			mappings
		)).containsOnlyKeys("003550");
	}

	@Test
	void acceptsUnambiguousCompanyNameInExcerpt() {
		var mappings = List.of(mapping("005930", "삼성전자", "Samsung Electronics"));

		assertThat(matcher.matchArticle(
			"반도체 업계 설비 투자 확대",
			"삼성전자는 신규 생산라인 구축 계획을 발표했다",
			mappings
		)).containsOnlyKeys("005930");
	}

	@Test
	void doesNotMatchKoreanCompanyNameInsideAProductName() {
		var mappings = List.of(mapping("035720", "카카오", "Kakao Corp."));

		assertThat(matcher.matchArticle(
			"법원, 강력 사건 피고인에게 무기징역 선고",
			"재판부는 카카오톡 대화 기록을 증거로 검토했다",
			mappings
		)).isEmpty();
	}

	@Test
	void requiresIssuerEvidenceInBothHeadlineAndActualBody() {
		var mappings = List.of(mapping("005930", "삼성전자", "Samsung Electronics"),
			mapping("000660", "SK하이닉스", "SK hynix"));
		assertThat(matcher.verifiedArticleMatches("삼성전자, 반도체 공급 확대", "SK하이닉스의 신규 생산 계획이다.", mappings)).isEmpty();
		assertThat(matcher.verifiedArticleMatches("삼성전자, 반도체 공급 확대", "삼성전자가 반도체 공급을 확대한다. 경쟁사 SK하이닉스도 언급했다.", mappings))
			.containsOnlyKeys("005930");
		assertThat(matcher.verifiedArticleMatches("삼성전자와 SK하이닉스, 반도체 공급 확대", "삼성전자와 SK하이닉스가 각각 신규 계약을 발표했다.", mappings))
			.containsOnlyKeys("005930", "000660");
	}

	@Test
	void rejectsDailyQuizEvenWhenStockKeywordAppears() {
		var mappings = List.of(mapping("323410", "카카오뱅크", "KakaoBank"));
		assertThat(matcher.verifiedArticleMatches("카카오뱅크 주식 퀴즈 정답 9월 2일", "카카오뱅크의 오늘 퀴즈 정답을 공개한다.", mappings)).isEmpty();
	}

	@Test
	void rejectsSponsoredTeamArticleWithSportsEvidenceOnlyInBody() {
		var mappings = List.of(mapping("012330", "현대모비스", "Hyundai Mobis"));
		assertThat(matcher.verifiedArticleMatches("현대모비스, 새로운 도전", "현대모비스 농구 선수들이 전지훈련을 시작했다.", mappings)).isEmpty();
	}

	@Test
	void doesNotConfuseCorporateOversightOrFilmDirectorsWithSports() {
		var mappings = List.of(mapping("032640", "LG유플러스", "LG Uplus"),
			mapping("010130", "고려아연", "Korea Zinc"));
		assertThat(matcher.verifiedArticleMatches("LG유플러스, 프리즈 서울 공식 파트너 참여",
			"LG유플러스가 영화감독과 함께 미디어아트 전시를 연다.", mappings)).containsOnlyKeys("032640");
		assertThat(matcher.verifiedArticleMatches("고려아연 경영진 지지 권고",
			"고려아연의 경영감독 기능과 이사회 독립성을 평가했다.", mappings)).containsOnlyKeys("010130");
	}

	@Test
	void rejectsSidebarAndPollBodiesRatherThanUsingSearchSnippet() {
		var mappings = List.of(mapping("005930", "삼성전자", "Samsung Electronics"));
		assertThat(matcher.verifiedArticleMatches("삼성전자 실적 발표", "Best Click 삼성전자 실적 발표", mappings)).isEmpty();
		assertThat(matcher.verifiedArticleMatches("삼성전자 실적 발표", "슈퍼스타 브랜드 파워 투표 삼성전자", mappings)).isEmpty();
	}

	private NewsStockMapping mapping(String stockCode, String nameKo, String nameEn) {
		return new NewsStockMapping(stockCode, nameKo, nameEn, "KOSPI", List.of(
			stockCode,
			nameKo,
			nameEn
		));
	}
}
