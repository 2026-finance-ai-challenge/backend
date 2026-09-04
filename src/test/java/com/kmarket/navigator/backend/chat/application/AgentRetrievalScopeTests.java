package com.kmarket.navigator.backend.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.stock.domain.StockIdentity;

class AgentRetrievalScopeTests {
	private static StockIdentity stock(String code, String ko, String en) {
		return new StockIdentity(UUID.randomUUID(), code, ko, en, "KOSPI", "", false);
	}

	private final StockIdentity samsung = stock("005930", "삼성전자", "Samsung Electronics Co., Ltd.");

	@Test
	void recognizesSingularEarningAndBoundedTypo() {
		var scope = AgentRetrievalScope.parse("can you tell me about recent samsung electonic's earning?", List.of(samsung));
		assertThat(scope.stocks()).containsExactly(samsung);
		assertThat(scope.financials()).isTrue();
	}

	@Test
	void usesRegisteredShortAliasWithoutOverridingExactAffiliate() {
		var sdi = stock("006400", "삼성SDI", "Samsung SDI Co., Ltd.");
		var aliases = java.util.Map.of("005930", List.of("Samsung"));
		assertThat(AgentRetrievalScope.parse("samsung's recent filing", List.of(samsung, sdi), aliases).stocks()).containsExactly(samsung);
		assertThat(AgentRetrievalScope.parse("Samsung SDI recent filing", List.of(samsung, sdi), aliases).stocks()).containsExactly(sdi);
		assertThat(AgentRetrievalScope.parse("삼성전자 이익", List.of(samsung)).financials()).isTrue();
	}

	@Test
	void resolvesIssuerAndLatestNewsWithoutExtraModelRequest() {
		var scope = AgentRetrievalScope.parse("Latest news about Samsung Electronics (005930)", List.of(samsung));
		assertThat(scope.stocks()).containsExactly(samsung);
		assertThat(scope.news()).isTrue();
		assertThat(scope.filings()).isFalse();
		assertThat(scope.includeLatest()).isTrue();
		assertThat(scope.from()).isNull();
	}

	@Test
	void includesLatestAndExplicitHistoricalMonthSeparately() {
		var scope = AgentRetrievalScope.parse("Samsung Electronics latest DART filing and one from August 2026", List.of(samsung));
		assertThat(scope.stocks()).containsExactly(samsung);
		assertThat(scope.filings()).isTrue();
		assertThat(scope.includeLatest()).isTrue();
		assertThat(scope.from()).isEqualTo(LocalDate.of(2026, 8, 1));
		assertThat(scope.to()).isEqualTo(LocalDate.of(2026, 8, 31));
	}

	@Test
	void koreanHistoricalRequestDoesNotUseCurrentFeed() {
		var scope = AgentRetrievalScope.parse("삼성전자 2026년 8월 공시", List.of(samsung));
		assertThat(scope.stocks()).containsExactly(samsung);
		assertThat(scope.includeLatest()).isFalse();
		assertThat(scope.from()).isEqualTo(LocalDate.of(2026, 8, 1));
	}

	@Test
	void unsupportedSymbolDoesNotFallBackToMarketNews() {
		var scope = AgentRetrievalScope.parse("Latest news about 999999", List.of(samsung));
		assertThat(scope.stocks()).isEmpty();
		assertThat(scope.unknownSymbol()).isTrue();
	}

	@Test
	void distinguishesParentCompanyFromSubsidiary() {
		var parent = stock("000150", "두산", "Doosan Corporation");
		var subsidiary = stock("034020", "두산에너빌리티", "Doosan Enerbility Co., Ltd.");
		assertThat(AgentRetrievalScope.parse("두산에너빌리티 뉴스", List.of(parent, subsidiary)).stocks())
			.containsExactly(subsidiary);
		assertThat(AgentRetrievalScope.parse("Doosan Enerbility news", List.of(parent, subsidiary)).stocks())
			.containsExactly(subsidiary);
		assertThat(AgentRetrievalScope.parse("000150 and 034020 news", List.of(parent, subsidiary)).stocks())
			.containsExactly(parent, subsidiary);
	}

	@Test
	void preservesAlphanumericTicker() {
		var stock = stock("0123A0", "테스트", "Example Co., Ltd.");
		assertThat(AgentRetrievalScope.parse("0123a0 latest news", List.of(stock)).stocks()).containsExactly(stock);
	}
}
