package com.kmarket.navigator.backend.chat.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.chat.domain.AgentEvidence;
import com.kmarket.navigator.backend.chat.domain.ChatContext;
import com.kmarket.navigator.backend.news.application.NewsService;
import com.kmarket.navigator.backend.stock.application.MarketService;
import com.kmarket.navigator.backend.disclosure.application.DisclosureQueryHandler;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.news.domain.NewsQuery;
import com.kmarket.navigator.backend.news.domain.NewsSort;

import tools.jackson.databind.ObjectMapper;

@Component
public class AgentEvidenceProvider {

	private static final int MAX_EVIDENCE_CHARACTERS = 12_000;
	private final MarketService marketService;
	private final NewsService newsService;
	private final ObjectMapper objectMapper;
	private final DisclosureQueryHandler disclosures;
	private final com.kmarket.navigator.backend.disclosure.application.port.DisclosureRagGateway disclosureRag;
	private final com.kmarket.navigator.backend.translation.application.NewsSelectionValidator newsSelections;

	public AgentEvidenceProvider(
		MarketService marketService,
		NewsService newsService,
		ObjectMapper objectMapper,
		DisclosureQueryHandler disclosures,
		com.kmarket.navigator.backend.translation.application.NewsSelectionValidator newsSelections,
		com.kmarket.navigator.backend.disclosure.application.port.DisclosureRagGateway disclosureRag
	) {
		this.marketService = marketService;
		this.newsService = newsService;
		this.objectMapper = objectMapper;
		this.disclosures = disclosures;
		this.newsSelections = newsSelections;
		this.disclosureRag = disclosureRag;
	}

	public List<AgentEvidence> evidence(ChatContext context, String question) {
		return evidence(context, question, null);
	}

	public List<AgentEvidence> evidence(ChatContext context, String question, String selectedText) {
		List<AgentEvidence> retrieved = switch (context.type()) {
			case GENERAL -> questionEvidence(context, question);
			case STOCK -> questionEvidence(context, question);
			case NEWS -> newsEvidence(context.referenceId(), selectedText);
			case TAX_GUIDE -> List.of();
			case FILING -> throw new IllegalArgumentException("Filing context uses disclosure RAG");
		};
		// AI 계약의 짧은 ID로만 인용하고 실제 문서 식별자는 서버에 보존한다.
		List<AgentEvidence> numbered = new ArrayList<>();
		for (var item : retrieved) {
			numbered.add(new AgentEvidence("E" + (numbered.size() + 1), item.title(), item.content(),
				item.source() == null || item.source().isBlank() ? "Unspecified publisher" : item.source(),
				item.asOf(), item.referenceId(), item.url()));
		}
		return List.copyOf(numbered);
	}

	private List<AgentEvidence> questionEvidence(ChatContext context, String question) {
		var scope = AgentRetrievalScope.parse(question, marketService.searchStocks("", null, 100), marketService.stockAliases());
		List<String> codes = context.type() == com.kmarket.navigator.backend.chat.domain.ChatContextType.STOCK
			? List.of(context.referenceId()) : scope.stocks().stream().map(stock -> stock.stockCode()).toList();
		List<AgentEvidence> result = new ArrayList<>();
		if (!scope.news() && !scope.filings() && !scope.financials()) {
			if (codes.isEmpty()) return generalEvidence();
			for (String code : codes) result.addAll(stockEvidence(code).stream().map(item -> new AgentEvidence(
				"S" + code, item.title(), item.content(), item.source(), item.asOf(), item.referenceId(), item.url())).toList());
			return List.copyOf(result);
		}
		if (scope.unknownSymbol()) return List.of();
		// 질문에 나온 지원 종목·기간으로 조회하며, 전체 시장 자료를 특정 종목의 근거로 위장하지 않는다.
		List<String> targets = codes.isEmpty() ? java.util.Collections.singletonList(null) : codes;
		Map<String, AgentEvidence> found = new LinkedHashMap<>();
		for (String code : targets) {
			if (scope.includeLatest()) addFeedEvidence(found, code, null, null, scope.news(), scope.filings());
			if (scope.from() != null) addFeedEvidence(found, code, scope.from(), scope.to(), scope.news(), scope.filings());
		}
		if (!codes.isEmpty() && (scope.filings() || scope.financials())) {
			if (scope.includeLatest()) addRagEvidence(found, codes, question, null, null, scope.financials());
			if (scope.from() != null) addRagEvidence(found, codes, question, scope.from(), scope.to(), scope.financials());
		}
		return found.values().stream().limit(12).toList();
	}

	private void addRagEvidence(Map<String, AgentEvidence> found, List<String> codes, String question,
		LocalDate from, LocalDate to, boolean financials) {
		for (var filing : disclosureRag.retrieve(codes, question, from, to, financials)) {
			if (!codes.contains(filing.stockCode()) || !filing.receiptNumber().matches("[0-9]{14}")) continue;
			Map<String, Object> packet = new LinkedHashMap<>();
			packet.put("kind", "FILING_SOURCE_EXCERPT"); packet.put("stockCode", filing.stockCode());
			packet.put("title", filing.title()); packet.put("filedDate", filing.filedDate());
			packet.put("receiptNumber", filing.receiptNumber()); packet.put("sectionIds", filing.sectionIds());
			packet.put("retrievalMethod", filing.retrievalMethod());
			packet.put("sourceText", excerpt(filing.content(), 7200));
			String key = "F" + filing.receiptNumber();
			found.put(key, new AgentEvidence(key, filing.title(), newsJson(packet), "OpenDART", filing.detectedAt(),
				filing.receiptNumber(), "/disclosures/" + filing.receiptNumber()));
		}
	}

	private void addFeedEvidence(Map<String, AgentEvidence> found, String stockCode, LocalDate from,
		LocalDate to, boolean includeNews, boolean includeFilings) {
		var zone = ZoneId.of("Asia/Seoul");
		if (includeNews) {
			var page = newsService.findAll(new NewsQuery(null, stockCode, null, null, null, null, false, null,
				from == null ? null : from.atStartOfDay(zone).toInstant(),
				to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1),
				NewsSort.LATEST, null, 3));
			for (var article : page.items()) {
				Map<String, Object> packet = new LinkedHashMap<>();
				packet.put("kind", "NEWS"); packet.put("stockCode", stockCode);
				packet.put("title", article.englishTitle()); packet.put("originalTitle", article.originalTitle());
				packet.put("publishedAt", article.publishedAt()); packet.put("sourceUrl", article.originalUrl());
				packet.put("sourceExcerpt", excerpt(article.sourceText(), 1800));
				String key = "N" + article.id();
				// 대화 출처는 외부 검색 결과가 아니라 서비스에 수집·검증된 기사 상세로 연결한다.
				found.putIfAbsent(key, new AgentEvidence(key, article.englishTitle(), objectMapper.writeValueAsString(packet),
					article.publisher(), article.publishedAt(), article.id().toString(), "/news/" + article.id()));
			}
		}
		if (includeFilings) {
			var page = disclosures.findAll(new DisclosureListQuery(stockCode, from, to, java.util.Set.of(), null, 3));
			for (var filing : page.items()) {
				Map<String, Object> packet = new LinkedHashMap<>();
				packet.put("kind", "FILING_METADATA"); packet.put("stockCode", filing.stockCode());
				packet.put("issuer", filing.issuerNameEn()); packet.put("title", filing.titleEn());
				packet.put("originalTitle", filing.titleKo()); packet.put("filedDate", filing.filedDate());
				packet.put("sourceUrl", "/disclosures/" + filing.receiptNumber()); packet.put("receiptNumber", filing.receiptNumber());
				packet.put("evidenceScope", "Filing metadata only; do not infer document contents.");
				String key = "F" + filing.receiptNumber();
				found.putIfAbsent(key, new AgentEvidence(key, filing.titleEn(), objectMapper.writeValueAsString(packet),
					"OpenDART", filing.detectedAt(), filing.receiptNumber(), "/disclosures/" + filing.receiptNumber()));
			}
		}
	}

	private static String excerpt(String value, int limit) {
		return value == null ? "" : value.substring(0, Math.min(value.length(), limit));
	}

	private List<AgentEvidence> generalEvidence() {
		List<AgentEvidence> evidence = new ArrayList<>();
		for (var index : marketService.marketIndices()) {
			Map<String, Object> packet = new LinkedHashMap<>();
			packet.put("indexName", index.indexName());
			packet.put("currentValue", index.currentValue());
			packet.put("changeAmount", index.changeAmount());
			packet.put("changeRate", index.changeRate());
			packet.put("dataStatus", index.dataStatus());
			evidence.add(new AgentEvidence(
				"E" + (evidence.size() + 1),
				index.indexName() + " server snapshot",
				json(packet),
				index.source(),
				index.asOf(),
				index.indexCode(),
				null
			));
		}
		return List.copyOf(evidence);
	}

	private List<AgentEvidence> stockEvidence(String stockCode) {
		var detail = marketService.stockDetail(stockCode, null);
		var quote = detail.view().quote();
		return List.of(new AgentEvidence(
			"E1",
			detail.view().stock().nameEn() + " server market snapshot",
			json(detail),
			quote == null ? "UNAVAILABLE" : quote.source(),
			quote == null ? null : quote.asOf(),
			stockCode,
			null
		));
	}

	private List<AgentEvidence> newsEvidence(String articleId, String selectedText) {
		var article = newsService.findOne(java.util.UUID.fromString(articleId));
		var selection = newsSelections.validate(article, selectedText);
		Map<String, Object> packet = new LinkedHashMap<>();
		packet.put("title", article.originalTitle());
		if (selection != null) packet.put("verifiedSelection", selection);
		packet.put("sourceText", selection != null && selection.language().equals("ko")
			? selection.context() : excerpt(article.sourceText(), 6_000));
		packet.put("evidenceScope", "Excerpts from this article only, not the complete article.");
		packet.put("what", article.whatEn());
		packet.put("why", article.whyEn());
		packet.put("impact", article.impactEn());
		packet.put("sentiment", article.sentiment());
		packet.put("importance", article.importance());
		packet.put("marketImpact", article.marketImpact());
		packet.put("marketImpactImportance", article.marketImpactImportance());
		packet.put("marketImpactScore", article.marketImpactScore());
		packet.put("analysisStatus", article.analysisStatus());
		return List.of(new AgentEvidence(
			"E1",
			article.englishTitle(),
			newsJson(packet),
			article.publisher() == null ? "Naver News" : article.publisher(),
			article.publishedAt(),
			article.id().toString(),
			article.originalUrl()
		));
	}

	private String newsJson(Map<String, Object> packet) {
		String serialized = objectMapper.writeValueAsString(packet);
		String source = (String) packet.get("sourceText");
		// JSON 문자열 자체를 잘라 선택문이나 근거 구조를 손상시키지 않는다.
		while (serialized.length() > MAX_EVIDENCE_CHARACTERS && !source.isEmpty()) {
			source = source.substring(0, Math.max(0, source.length() - (serialized.length() - MAX_EVIDENCE_CHARACTERS)));
			packet.put("sourceText", source);
			serialized = objectMapper.writeValueAsString(packet);
		}
		if (serialized.length() > MAX_EVIDENCE_CHARACTERS) throw new com.kmarket.navigator.backend.global.error.BusinessException(
			com.kmarket.navigator.backend.global.error.ErrorCode.INVALID_CHAT_SELECTION);
		return serialized;
	}

	private String json(Object value) {
		String serialized = objectMapper.writeValueAsString(value);
		return serialized.substring(0, Math.min(serialized.length(), MAX_EVIDENCE_CHARACTERS));
	}
}
