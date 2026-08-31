package com.kmarket.navigator.backend.stock.infrastructure.exchange;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.stock.application.port.ExchangeRateGateway;
import com.kmarket.navigator.backend.stock.domain.ExchangeRateSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketDataStatus;

import tools.jackson.databind.JsonNode;

@Component
class FrankfurterExchangeRateGateway implements ExchangeRateGateway {

	private final RestClient restClient;
	private final FrankfurterProperties properties;

	FrankfurterExchangeRateGateway(
		@Qualifier("frankfurterRestClient") RestClient restClient,
		FrankfurterProperties properties
	) {
		this.restClient = restClient;
		this.properties = properties;
	}

	@Override
	public Optional<ExchangeRateSnapshot> fetchUsdKrw() {
		if (!properties.isEnabled()) {
			return Optional.empty();
		}
		JsonNode response = restClient.get()
			.uri("/v2/rate/USD/KRW")
			.retrieve()
			.body(JsonNode.class);
		if (response == null) {
			return Optional.empty();
		}
		String base = text(response, "base");
		String quote = text(response, "quote");
		String dateValue = text(response, "date");
		BigDecimal rate = decimal(response, "rate");
		if (!"USD".equals(base) || !"KRW".equals(quote) || rate == null || rate.signum() <= 0) {
			return Optional.empty();
		}
		LocalDate date = LocalDate.parse(dateValue);
		return Optional.of(new ExchangeRateSnapshot(
			"USD", rate, MarketDataStatus.CLOSED,
			date.atStartOfDay().toInstant(ZoneOffset.UTC), "FRANKFURTER_V2"
		));
	}

	private String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return "";
		}
		String text = value.stringValue();
		return (text == null ? value.toString() : text).trim();
	}

	private BigDecimal decimal(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (value.isNumber()) {
			return value.decimalValue();
		}
		try {
			return new BigDecimal(value.stringValue().trim());
		} catch (NumberFormatException exception) {
			return null;
		}
	}
}
