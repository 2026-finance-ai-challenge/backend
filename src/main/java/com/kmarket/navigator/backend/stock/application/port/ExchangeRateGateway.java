package com.kmarket.navigator.backend.stock.application.port;

import java.util.Optional;

import com.kmarket.navigator.backend.stock.domain.ExchangeRateSnapshot;

public interface ExchangeRateGateway {

	Optional<ExchangeRateSnapshot> fetchUsdKrw();
}
