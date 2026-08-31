package com.kmarket.navigator.backend.stock.infrastructure.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.kmarket.navigator.backend.disclosure.infrastructure.ai.AiServiceProperties;
import com.kmarket.navigator.backend.stock.application.port.ForeignLimitPredictionGateway;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPrediction;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Component
class AiForeignLimitPredictionClient implements ForeignLimitPredictionGateway {

	private static final Logger log = LoggerFactory.getLogger(AiForeignLimitPredictionClient.class);
	private static final int MINIMUM_OBSERVATIONS = 20;
	private final RestClient restClient;
	private final AiServiceProperties properties;

	AiForeignLimitPredictionClient(
		@Qualifier("aiServiceRestClient") RestClient restClient,
		AiServiceProperties properties
	) {
		this.restClient = restClient;
		this.properties = properties;
	}

	@Override
	public Optional<ForeignLimitPrediction> predict(
		String stockCode,
		List<ForeignOwnershipSnapshot> history
	) {
		List<ForeignOwnershipSnapshot> ordered = history.stream()
			.filter(this::usable)
			.sorted(Comparator.comparing(ForeignOwnershipSnapshot::baseDate))
			.toList();
		if (ordered.size() < MINIMUM_OBSERVATIONS
			|| properties.serviceToken() == null
			|| properties.serviceToken().isBlank()) {
			return Optional.empty();
		}
		ForeignOwnershipSnapshot latest = ordered.getLast();
		try {
			Response response = restClient.post()
				.uri("/internal/v1/market/foreign-ownership/forecast")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
				.body(Request.from(stockCode, latest, ordered))
				.retrieve()
				.body(Response.class);
			return response == null ? Optional.empty() : Optional.of(response.toDomain());
		}
		catch (RestClientException | IllegalArgumentException exception) {
			log.warn(
				"AI foreign ownership forecast failed stockCode={} type={}",
				stockCode,
				exception.getClass().getSimpleName()
			);
			return Optional.empty();
		}
	}

	private boolean usable(ForeignOwnershipSnapshot snapshot) {
		return snapshot.foreignOwnedQuantity() > 0
			&& snapshot.totalListedQuantity() != null
			&& snapshot.totalListedQuantity() > 0
			&& snapshot.foreignLimitQuantity() != null
			&& snapshot.foreignLimitQuantity() > 0;
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Request(
		String stockCode,
		long foreignOwnedQuantity,
		long totalListedQuantity,
		long foreignLimitQuantity,
		LocalDate baseDate,
		List<HistoryPoint> history
	) {
		static Request from(
			String stockCode,
			ForeignOwnershipSnapshot latest,
			List<ForeignOwnershipSnapshot> history
		) {
			return new Request(
				stockCode,
				latest.foreignOwnedQuantity(),
				latest.totalListedQuantity(),
				latest.foreignLimitQuantity(),
				latest.baseDate(),
				history.stream().map(HistoryPoint::from).toList()
			);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record HistoryPoint(
		LocalDate baseDate,
		long foreignOwnedQuantity,
		long foreignLimitQuantity
	) {
		static HistoryPoint from(ForeignOwnershipSnapshot snapshot) {
			return new HistoryPoint(
				snapshot.baseDate(),
				snapshot.foreignOwnedQuantity(),
				snapshot.foreignLimitQuantity()
			);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Response(
		BigDecimal minRate,
		BigDecimal baseRate,
		BigDecimal maxRate,
		int observationCount,
		int observationWindowDays,
		BigDecimal confidence,
		String modelVersion,
		LocalDate baseDate,
		Instant calculatedAt,
		String source
	) {
		ForeignLimitPrediction toDomain() {
			return new ForeignLimitPrediction(
				minRate,
				baseRate,
				maxRate,
				observationCount,
				observationWindowDays,
				confidence,
				modelVersion,
				baseDate,
				calculatedAt,
				source
			);
		}
	}
}
