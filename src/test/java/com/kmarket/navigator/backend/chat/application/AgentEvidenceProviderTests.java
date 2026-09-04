package com.kmarket.navigator.backend.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.kmarket.navigator.backend.chat.domain.ChatContext;
import com.kmarket.navigator.backend.chat.domain.ChatContextType;
import com.kmarket.navigator.backend.disclosure.application.DisclosureQueryHandler;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.disclosure.domain.DisclosurePage;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSummary;
import com.kmarket.navigator.backend.news.application.NewsService;
import com.kmarket.navigator.backend.news.domain.NewsArticle;
import com.kmarket.navigator.backend.news.domain.NewsPage;
import com.kmarket.navigator.backend.news.domain.NewsQuery;
import com.kmarket.navigator.backend.news.domain.NewsSort;
import com.kmarket.navigator.backend.stock.application.MarketService;
import com.kmarket.navigator.backend.stock.domain.StockIdentity;

import tools.jackson.databind.json.JsonMapper;

class AgentEvidenceProviderTests {
	private final MarketService market = mock(MarketService.class);
	private final NewsService news = mock(NewsService.class);
	private final DisclosureQueryHandler filings = mock(DisclosureQueryHandler.class);
	private final com.kmarket.navigator.backend.translation.application.NewsSelectionValidator selections = mock(com.kmarket.navigator.backend.translation.application.NewsSelectionValidator.class);
	private final com.kmarket.navigator.backend.disclosure.application.port.DisclosureRagGateway rag = mock(com.kmarket.navigator.backend.disclosure.application.port.DisclosureRagGateway.class);
	private final AgentEvidenceProvider provider = new AgentEvidenceProvider(market, news, new JsonMapper(), filings, selections, rag);
	private final ChatContext context = new ChatContext(ChatContextType.GENERAL, null, null, "Market assistant");

	AgentEvidenceProviderTests() {
		when(market.searchStocks("", null, 100)).thenReturn(List.of(new StockIdentity(
			UUID.randomUUID(), "005930", "삼성전자", "Samsung Electronics Co., Ltd.", "KOSPI", "", false)));
	}

	@Test
	void retrievesFinancialSourceForMisspelledEnglishQuestion() {
		String question = "can you tell me about recent samsung electonic's earning?";
		when(rag.retrieve(List.of("005930"), question, null, null, true)).thenReturn(List.of(
			new com.kmarket.navigator.backend.disclosure.domain.FilingEvidence("20260814000001", "005930", "반기보고서",
				LocalDate.of(2026, 8, 14), Instant.parse("2026-08-14T01:00:00Z"), "연결 매출액 100, 영업이익 20 (단위: 백만원)", List.of(), "CURRENT_VECTOR_CHUNKS")));
		var evidence = provider.evidence(context, question);
		assertThat(evidence).hasSize(1);
		assertThat(evidence.getFirst().content()).contains("영업이익", "CURRENT_VECTOR_CHUNKS");
		assertThat(evidence.getFirst().url()).isEqualTo("/disclosures/20260814000001");
		verifyNoInteractions(news, filings);
	}

	@Test
	void retrievesActualIssuerNewsWithDateAndSource() {
		var article = mock(NewsArticle.class);
		when(article.id()).thenReturn(UUID.randomUUID());
		when(article.englishTitle()).thenReturn("Samsung chip production update");
		when(article.originalTitle()).thenReturn("삼성전자 반도체 생산 소식");
		when(article.publishedAt()).thenReturn(Instant.parse("2026-09-02T01:00:00Z"));
		when(article.originalUrl()).thenReturn("https://news.example.com/123");
		when(article.sourceText()).thenReturn("Source text ".repeat(300));
		when(news.findAll(any())).thenReturn(new NewsPage(List.of(article), null));

		var evidence = provider.evidence(context, "Latest Samsung Electronics (005930) news");

		var query = ArgumentCaptor.forClass(NewsQuery.class);
		Mockito.verify(news).findAll(query.capture());
		assertThat(query.getValue().stockCode()).isEqualTo("005930");
		assertThat(query.getValue().sort()).isEqualTo(NewsSort.LATEST);
		assertThat(query.getValue().limit()).isEqualTo(3);
		assertThat(evidence).hasSize(1);
		assertThat(evidence.getFirst().id()).isEqualTo("E1");
		assertThat(evidence.getFirst().source()).isNotBlank();
		assertThat(evidence.getFirst().url()).isEqualTo("/news/" + article.id());
		assertThat(evidence.getFirst().content()).contains("2026-09-02T01:00:00Z", "005930").endsWith("}");
		verifyNoInteractions(filings);
	}

	@Test
	void queriesLatestAndHistoricalFilingsWithoutPretendingMetadataIsDocumentContent() {
		var filing = mock(DisclosureSummary.class);
		when(filing.receiptNumber()).thenReturn("20260902800513");
		when(filing.titleEn()).thenReturn("Corporate filing");
		when(filing.stockCode()).thenReturn("005930");
		when(filing.filedDate()).thenReturn(LocalDate.of(2026, 9, 2));
		when(filing.officialUrl()).thenReturn("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260902800513");
		when(filings.findAll(any())).thenReturn(new DisclosurePage(List.of(filing), null));

		var evidence = provider.evidence(context, "Find Samsung Electronics latest DART filing and one from August 2026");

		var query = ArgumentCaptor.forClass(DisclosureListQuery.class);
		Mockito.verify(filings, Mockito.times(2)).findAll(query.capture());
		assertThat(query.getAllValues()).allMatch(item -> "005930".equals(item.stockCode()));
		assertThat(query.getAllValues().getFirst().from()).isNull();
		assertThat(query.getAllValues().getLast().from()).isEqualTo(LocalDate.of(2026, 8, 1));
		assertThat(query.getAllValues().getLast().to()).isEqualTo(LocalDate.of(2026, 8, 31));
		assertThat(evidence).hasSize(1);
		assertThat(evidence.getFirst().id()).matches("^E[0-9]{1,3}$");
		assertThat(evidence.getFirst().content()).contains("FILING_METADATA", "do not infer document contents");
		assertThat(evidence.getFirst().url()).isEqualTo("/disclosures/20260902800513");
		assertThat(evidence.getFirst().content()).doesNotContain("dart.fss.or.kr");
		verifyNoInteractions(news);
	}

	@Test
	void preservesVerifiedSelectionAndValidJsonWhenArticleNeedsTrimming() {
		var article = mock(NewsArticle.class);
		UUID id = UUID.randomUUID();
		when(article.id()).thenReturn(id);
		when(article.originalTitle()).thenReturn("삼성전자 투자 전망");
		when(article.sourceText()).thenReturn("\"".repeat(10_000));
		when(news.findOne(id)).thenReturn(article);
		String selected = "An acquisition remains unconfirmed.";
		var selection = new com.kmarket.navigator.backend.translation.application.NewsSelectionValidator.Selection(
			selected, "en", "a".repeat(64), "Before ".repeat(140) + selected + " After".repeat(140));
		when(selections.validate(article, selected)).thenReturn(selection);

		var evidence = provider.evidence(new ChatContext(ChatContextType.NEWS, id.toString(), null, "News"), "Explain this.", selected);
		String serialized = evidence.getFirst().content();
		var packet = new JsonMapper().readTree(serialized);
		assertThat(serialized).hasSizeLessThanOrEqualTo(12_000);
		assertThat(packet.path("verifiedSelection").path("text").asString()).isEqualTo(selected);
		assertThat(packet.path("verifiedSelection").path("context").asString()).isEqualTo(selection.context());
		assertThat(packet.path("sourceText").asString()).isNotEmpty();
		assertThat(packet.path("evidenceScope").asString()).contains("not the complete article");
		verifyNoInteractions(market, filings);
	}

	@Test
	void unsupportedTickerDoesNotRetrieveUnrelatedFeeds() {
		assertThat(provider.evidence(context, "Latest 999999 news and filings")).isEmpty();
		verifyNoInteractions(news, filings);
	}
}
