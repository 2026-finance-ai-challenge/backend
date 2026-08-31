package com.kmarket.navigator.backend.stock.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record GlobalPeerAnalysis(
	String stockCode,
	String stockName,
	String stockNameEn,
	String market,
	String targetSector,
	String targetIndustry,
	String targetBusinessModel,
	String headline,
	String summary,
	GlobalPeer primaryPeer,
	List<GlobalPeer> peers,
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
	public GlobalPeerAnalysis {
		peers = List.copyOf(peers);
		comparisons = List.copyOf(comparisons);
		keyStrengths = List.copyOf(keyStrengths);
		if (peers.size() != 3 || comparisons.size() != 3 || keyStrengths.size() != 4) {
			throw new IllegalArgumentException("Global peer analysis cardinality is invalid");
		}
		if (!primaryPeer.equals(peers.getFirst())) {
			throw new IllegalArgumentException("Primary peer must be the first ranked peer");
		}
		if (confidenceScore == null
			|| confidenceScore.signum() < 0
			|| confidenceScore.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("Global peer confidence is invalid");
		}
	}

	public record GlobalPeer(
		String dimension,
		int rank,
		String ticker,
		String companyName,
		String logoUrl,
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
		public GlobalPeer {
			businessTags = List.copyOf(businessTags);
			if (rank < 1 || rank > 3 || similarityScore == null
				|| similarityScore.signum() < 0
				|| similarityScore.compareTo(BigDecimal.ONE) > 0) {
				throw new IllegalArgumentException("Global peer rank or score is invalid");
			}
		}
	}

	public record Comparison(String dimension, String description, GlobalPeer peer) {
	}

	public record Strength(String title, String description, String iconKey) {
	}
}
