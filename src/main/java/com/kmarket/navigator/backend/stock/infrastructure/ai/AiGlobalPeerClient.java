package com.kmarket.navigator.backend.stock.infrastructure.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.kmarket.navigator.backend.disclosure.infrastructure.ai.AiServiceProperties;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.stock.application.port.GlobalPeerGateway;
import com.kmarket.navigator.backend.stock.domain.GlobalPeerAnalysis;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Component
class AiGlobalPeerClient implements GlobalPeerGateway {

	private final RestClient restClient;
	private final AiServiceProperties properties;

	AiGlobalPeerClient(
		@Qualifier("aiServiceRestClient") RestClient restClient,
		AiServiceProperties properties
	) {
		this.restClient = restClient;
		this.properties = properties;
	}

	@Override
	public GlobalPeerAnalysis analyze(String stockCode, String safetyIdentifier) {
		if (properties.serviceToken() == null || properties.serviceToken().isBlank()) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		try {
			Response response = restClient.post()
				.uri("/internal/v1/peers/{stockCode}", stockCode)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
				.body(new Request(safetyIdentifier))
				.retrieve()
				.body(Response.class);
			if (response == null) {
				throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
			}
			return response.toDomain();
		}
		catch (HttpClientErrorException.NotFound exception) {
			throw new BusinessException(ErrorCode.GLOBAL_PEER_DATA_UNAVAILABLE);
		}
		catch (RestClientException | IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Request(String safetyIdentifier) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Response(
		String stockCode,
		String stockName,
		String stockNameEn,
		String market,
		String targetSector,
		String targetIndustry,
		String targetBusinessModel,
		String headline,
		String summary,
		Peer primaryPeer,
		List<Peer> peers,
		List<Comparison> comparisons,
		List<Strength> keyStrengths,
		BigDecimal confidenceScore,
		String confidenceLevel,
		LocalDate financialDataAsOf,
		String rankerModelVersion,
		String narrativeModel,
		String promptVersion,
		String source
	) {
		GlobalPeerAnalysis toDomain() {
			return new GlobalPeerAnalysis(
				stockCode, stockName, stockNameEn, market, targetSector, targetIndustry,
				targetBusinessModel, headline, summary, primaryPeer.toDomain(),
				peers.stream().map(Peer::toDomain).toList(),
				comparisons.stream().map(Comparison::toDomain).toList(),
				keyStrengths.stream().map(Strength::toDomain).toList(),
				confidenceScore, confidenceLevel, financialDataAsOf, rankerModelVersion,
				narrativeModel, promptVersion, source
			);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Peer(
		String dimension,
		int rank,
		String ticker,
		String companyName,
		String exchange,
		String country,
		BigDecimal similarityScore,
		List<String> businessTags,
		String sector,
		String industry,
		String businessModel,
		String scaleBucket,
		Integer fiscalYear,
		BigDecimal marketCapUsd,
		BigDecimal revenueUsd,
		BigDecimal operatingIncomeUsd,
		BigDecimal netIncomeUsd,
		String financialDataSource,
		BigDecimal financialSimilarityScore
	) {
		GlobalPeerAnalysis.GlobalPeer toDomain() {
			return new GlobalPeerAnalysis.GlobalPeer(
				dimension, rank, ticker, companyName, exchange, country, similarityScore,
				businessTags, sector, industry, businessModel, scaleBucket, fiscalYear,
				marketCapUsd, revenueUsd, operatingIncomeUsd, netIncomeUsd,
				financialDataSource, financialSimilarityScore
			);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Comparison(String dimension, String description, Peer peer) {
		GlobalPeerAnalysis.Comparison toDomain() {
			return new GlobalPeerAnalysis.Comparison(dimension, description, peer.toDomain());
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Strength(String title, String description, String iconKey) {
		GlobalPeerAnalysis.Strength toDomain() {
			return new GlobalPeerAnalysis.Strength(title, description, iconKey);
		}
	}
}
