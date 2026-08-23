package com.kmarket.navigator.backend.stock.application.port;

import java.time.Instant;
import java.util.Optional;

import com.kmarket.navigator.backend.stock.domain.GlobalPeerAnalysis;

public interface GlobalPeerAnalysisRepository {

	Optional<GlobalPeerAnalysis> find(String stockCode, String dataVersion);

	void save(String stockCode, String dataVersion, GlobalPeerAnalysis analysis, Instant generatedAt);
}
