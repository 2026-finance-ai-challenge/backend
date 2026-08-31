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

	private NewsStockMapping mapping(String stockCode, String nameKo, String nameEn) {
		return new NewsStockMapping(stockCode, nameKo, nameEn, "KOSPI", List.of(
			stockCode,
			nameKo,
			nameEn
		));
	}
}
