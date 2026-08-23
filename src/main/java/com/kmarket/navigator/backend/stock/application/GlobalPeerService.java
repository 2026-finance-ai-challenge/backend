package com.kmarket.navigator.backend.stock.application;

import java.time.Instant;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.stock.application.port.GlobalPeerAnalysisRepository;
import com.kmarket.navigator.backend.stock.application.port.GlobalPeerGateway;
import com.kmarket.navigator.backend.stock.application.port.MarketRepository;
import com.kmarket.navigator.backend.stock.domain.GlobalPeerAnalysis;

@Service
public class GlobalPeerService {

	private final MarketRepository marketRepository;
	private final GlobalPeerAnalysisRepository analysisRepository;
	private final GlobalPeerGateway gateway;
	private final GlobalPeerProperties properties;
	private final GlobalPeerRateLimiter rateLimiter;
	private final GlobalPeerGenerationGuard generationGuard;

	public GlobalPeerService(
		MarketRepository marketRepository,
		GlobalPeerAnalysisRepository analysisRepository,
		GlobalPeerGateway gateway,
		GlobalPeerProperties properties,
		GlobalPeerRateLimiter rateLimiter,
		GlobalPeerGenerationGuard generationGuard
	) {
		this.marketRepository = marketRepository;
		this.analysisRepository = analysisRepository;
		this.gateway = gateway;
		this.properties = properties;
		this.rateLimiter = rateLimiter;
		this.generationGuard = generationGuard;
	}

	public GlobalPeerAnalysis analyze(String stockCode, String safetyIdentifier) {
		String normalized = stockCode.trim().toUpperCase(Locale.ROOT);
		marketRepository.findStock(normalized, null)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_STOCK));
		var cached = analysisRepository.find(normalized, properties.dataVersion());
		if (cached.isPresent()) {
			return cached.get();
		}
		rateLimiter.check(safetyIdentifier);
		var guard = generationGuard.acquire(normalized);
		try {
			cached = analysisRepository.find(normalized, properties.dataVersion());
			if (cached.isPresent()) {
				return cached.get();
			}
			GlobalPeerAnalysis analysis = gateway.analyze(normalized, safetyIdentifier);
			if (!normalized.equals(analysis.stockCode())) {
				throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
			}
			analysisRepository.save(normalized, properties.dataVersion(), analysis, Instant.now());
			return analysis;
		}
		finally {
			guard.close();
		}
	}
}
