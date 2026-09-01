package com.kmarket.navigator.backend.chat.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.chat.domain.AgentEvidence;
import com.kmarket.navigator.backend.chat.domain.ChatContext;
import com.kmarket.navigator.backend.news.application.NewsService;
import com.kmarket.navigator.backend.stock.application.MarketService;

import tools.jackson.databind.ObjectMapper;

@Component
public class AgentEvidenceProvider {

	private static final int MAX_EVIDENCE_CHARACTERS = 12_000;
	private final MarketService marketService;
	private final NewsService newsService;
	private final ObjectMapper objectMapper;

	public AgentEvidenceProvider(
		MarketService marketService,
		NewsService newsService,
		ObjectMapper objectMapper
	) {
		this.marketService = marketService;
		this.newsService = newsService;
		this.objectMapper = objectMapper;
	}

	public List<AgentEvidence> evidence(ChatContext context) {
		return switch (context.type()) {
			case GENERAL -> generalEvidence();
			case STOCK -> stockEvidence(context.referenceId());
			case NEWS -> newsEvidence(context.referenceId());
			case TAX_GUIDE -> List.of();
			case FILING -> throw new IllegalArgumentException("Filing context uses disclosure RAG");
		};
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

	private List<AgentEvidence> newsEvidence(String articleId) {
		var article = newsService.findOne(java.util.UUID.fromString(articleId));
		Map<String, Object> packet = new LinkedHashMap<>();
		packet.put("title", article.originalTitle());
		packet.put("sourceText", article.sourceText());
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
			article.originalTitle(),
			json(packet),
			article.publisher() == null ? "Naver News" : article.publisher(),
			article.publishedAt(),
			article.id().toString(),
			article.originalUrl()
		));
	}

	private String json(Object value) {
		String serialized = objectMapper.writeValueAsString(value);
		return serialized.substring(0, Math.min(serialized.length(), MAX_EVIDENCE_CHARACTERS));
	}
}
