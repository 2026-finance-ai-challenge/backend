package com.kmarket.navigator.backend.stock.application.port;

import com.kmarket.navigator.backend.stock.domain.GlobalPeerAnalysis;

public interface GlobalPeerGateway {

	GlobalPeerAnalysis analyze(String stockCode, String safetyIdentifier);
}
