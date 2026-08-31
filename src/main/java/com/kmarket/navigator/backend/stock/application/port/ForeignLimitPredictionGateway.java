package com.kmarket.navigator.backend.stock.application.port;

import java.util.List;
import java.util.Optional;

import com.kmarket.navigator.backend.stock.domain.ForeignLimitPrediction;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;

public interface ForeignLimitPredictionGateway {

	Optional<ForeignLimitPrediction> predict(
		String stockCode,
		List<ForeignOwnershipSnapshot> history
	);
}
