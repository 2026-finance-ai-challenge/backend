package com.kmarket.navigator.backend.stock.infrastructure.kis;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.kmarket.navigator.backend.stock.application.port.MarketDataGateway;
import com.kmarket.navigator.backend.stock.domain.MarketDataStatus;
import com.kmarket.navigator.backend.stock.domain.MarketIndexSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketQuoteSnapshot;
import com.kmarket.navigator.backend.stock.domain.PriceLimitState;

import tools.jackson.databind.JsonNode;

@Component
class KisMarketDataGateway implements MarketDataGateway {

	private static final String QUOTE_TRANSACTION_ID = "FHKST01010100";
	private static final String INDEX_TRANSACTION_ID = "FHPUP02100000";
	private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
	private static final Map<String, String> INDEX_NAMES = Map.of(
		"0001", "KOSPI",
		"1001", "KOSDAQ",
		"2001", "KOSPI 200"
	);
	private final RestClient restClient;
	private final KisMarketProperties properties;
	private final KisAccessTokenProvider tokenProvider;
	private final KisCircuitBreaker circuitBreaker;
	private final Clock clock;

	KisMarketDataGateway(
		@Qualifier("kisMarketRestClient") RestClient restClient,
		KisMarketProperties properties,
		KisAccessTokenProvider tokenProvider,
		KisCircuitBreaker circuitBreaker
	) {
		this.restClient = restClient;
		this.properties = properties;
		this.tokenProvider = tokenProvider;
		this.circuitBreaker = circuitBreaker;
		this.clock = Clock.system(KOREA_ZONE);
	}

	@Override
	public boolean configured() {
		return properties.configured();
	}

	@Override
	public Optional<MarketQuoteSnapshot> fetchQuote(String stockCode) {
		if (!configured()) {
			return Optional.empty();
		}
		JsonNode output = request(
			"/uapi/domestic-stock/v1/quotations/inquire-price",
			QUOTE_TRANSACTION_ID,
			"J",
			stockCode
		);
		BigDecimal current = decimal(output, "stck_prpr");
		if (current == null) {
			return Optional.empty();
		}
		String changeSign = text(output, "prdy_vrss_sign");
		BigDecimal changeAmount = signed(decimal(output, "prdy_vrss"), changeSign);
		BigDecimal changeRate = signed(decimal(output, "prdy_ctrt"), changeSign);
		BigDecimal upperLimit = decimal(output, "stck_mxpr");
		BigDecimal lowerLimit = decimal(output, "stck_llam");
		Boolean halted = yesNo(output, "temp_stop_yn");
		Instant now = clock.instant();
		return Optional.of(new MarketQuoteSnapshot(
			current,
			zeroIfNull(changeAmount),
			zeroIfNull(changeRate),
			decimal(output, "stck_oprc"),
			decimal(output, "stck_hgpr"),
			decimal(output, "stck_lwpr"),
			longValue(output, "acml_vol", 0L),
			marketSession(),
			null,
			null,
			priceLimit(current, upperLimit, lowerLimit),
			halted,
			Boolean.TRUE.equals(halted) ? "KIS_TEMPORARY_STOP" : null,
			false,
			marketDataStatus(),
			now,
			"KIS_REST_CURRENT_PRICE"
		));
	}

	@Override
	public Optional<MarketIndexSnapshot> fetchIndex(String indexCode) {
		if (!configured() || !INDEX_NAMES.containsKey(indexCode)) {
			return Optional.empty();
		}
		JsonNode output = request(
			"/uapi/domestic-stock/v1/quotations/inquire-index-price",
			INDEX_TRANSACTION_ID,
			"U",
			indexCode
		);
		BigDecimal current = firstDecimal(output, "bstp_nmix_prpr", "stck_prpr");
		if (current == null) {
			return Optional.empty();
		}
		String changeSign = firstText(output, "prdy_vrss_sign", "prdy_vrss_sign_name");
		return Optional.of(new MarketIndexSnapshot(
			indexCode,
			INDEX_NAMES.get(indexCode),
			current,
			zeroIfNull(signed(firstDecimal(output, "bstp_nmix_prdy_vrss", "prdy_vrss"), changeSign)),
			zeroIfNull(signed(firstDecimal(output, "bstp_nmix_prdy_ctrt", "prdy_ctrt"), changeSign)),
			longValue(output, "acml_vol", 0L),
			marketDataStatus(),
			clock.instant(),
			"KIS_REST_INDEX_PRICE"
		));
	}

	private JsonNode request(String path, String transactionId, String marketCode, String code) {
		String token = tokenProvider.accessToken();
		JsonNode root = circuitBreaker.execute(() -> requestWithRetry(
			path,
			transactionId,
			marketCode,
			code,
			token
		));
		if (root == null || !"0".equals(text(root, "rt_cd"))) {
			throw new KisProviderException("KIS market response was rejected");
		}
		JsonNode output = root.path("output");
		if (output.isMissingNode() || output.isNull()) {
			throw new KisProviderException("KIS market response has no output");
		}
		return output;
	}

	private JsonNode requestWithRetry(
		String path,
		String transactionId,
		String marketCode,
		String code,
		String token
	) {
		int maxAttempts = Math.max(1, properties.getRetryMaxAttempts());
		for (int attempt = 1; ; attempt++) {
			try {
				return exchange(path, transactionId, marketCode, code, token);
			} catch (RuntimeException exception) {
				if (attempt >= maxAttempts || !isTransient(exception)) {
					throw exception;
				}
				pauseBeforeRetry(attempt);
			}
		}
	}

	private JsonNode exchange(
		String path,
		String transactionId,
		String marketCode,
		String code,
		String token
	) {
		return restClient.get()
			.uri(uriBuilder -> uriBuilder
				.path(path)
				.queryParam("FID_COND_MRKT_DIV_CODE", marketCode)
				.queryParam("FID_INPUT_ISCD", code)
				.build())
			.header("Content-Type", "application/json; charset=utf-8")
			.header("authorization", "Bearer " + token)
			.header("appkey", properties.getAppKey())
			.header("appsecret", properties.getAppSecret())
			.header("tr_id", transactionId)
			.retrieve()
			.body(JsonNode.class);
	}

	private void pauseBeforeRetry(int failedAttempt) {
		Duration initialDelay = nonNegative(properties.getRetryInitialDelay());
		Duration maxDelay = nonNegative(properties.getRetryMaxDelay());
		long multiplier = 1L << Math.min(failedAttempt - 1, 20);
		Duration ceiling = min(initialDelay.multipliedBy(multiplier), maxDelay);
		if (ceiling.isZero()) {
			return;
		}
		long ceilingMillis = Math.max(1L, ceiling.toMillis());
		long delayMillis = ThreadLocalRandom.current().nextLong(
			Math.max(1L, ceilingMillis / 2),
			ceilingMillis + 1
		);
		try {
			Thread.sleep(Duration.ofMillis(delayMillis));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new KisProviderException("KIS market retry was interrupted", exception);
		}
	}

	private static boolean isTransient(RuntimeException exception) {
		if (exception instanceof ResourceAccessException) {
			return true;
		}
		if (exception instanceof RestClientResponseException responseException) {
			HttpStatusCode status = responseException.getStatusCode();
			return status.is5xxServerError() || status.value() == 429;
		}
		return false;
	}

	private static Duration nonNegative(Duration duration) {
		return duration == null || duration.isNegative() ? Duration.ZERO : duration;
	}

	private static Duration min(Duration first, Duration second) {
		if (second.isZero()) {
			return Duration.ZERO;
		}
		return first.compareTo(second) <= 0 ? first : second;
	}

	private MarketDataStatus marketDataStatus() {
		return "REGULAR".equals(marketSession()) ? MarketDataStatus.DELAYED : MarketDataStatus.CLOSED;
	}

	private String marketSession() {
		LocalDateTime now = LocalDateTime.now(clock);
		DayOfWeek day = now.getDayOfWeek();
		if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
			return "CLOSED";
		}
		LocalTime time = now.toLocalTime();
		if (time.isBefore(LocalTime.of(8, 30))) {
			return "CLOSED";
		}
		if (time.isBefore(LocalTime.of(9, 0))) {
			return "PRE_MARKET";
		}
		if (!time.isAfter(LocalTime.of(15, 30))) {
			return "REGULAR";
		}
		if (!time.isAfter(LocalTime.of(18, 0))) {
			return "AFTER_HOURS";
		}
		return "CLOSED";
	}

	private static PriceLimitState priceLimit(
		BigDecimal current,
		BigDecimal upperLimit,
		BigDecimal lowerLimit
	) {
		if (upperLimit != null && current.compareTo(upperLimit) >= 0) {
			return PriceLimitState.UPPER;
		}
		if (lowerLimit != null && current.compareTo(lowerLimit) <= 0) {
			return PriceLimitState.LOWER;
		}
		return PriceLimitState.NONE;
	}

	private static Boolean yesNo(JsonNode output, String field) {
		String value = text(output, field);
		return value.isBlank() ? null : "Y".equalsIgnoreCase(value);
	}

	private static BigDecimal signed(BigDecimal value, String changeSign) {
		if (value == null) {
			return null;
		}
		BigDecimal absolute = value.abs();
		return switch (changeSign) {
			case "1", "2" -> absolute;
			case "4", "5" -> absolute.negate();
			default -> value;
		};
	}

	private static BigDecimal zeroIfNull(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private static BigDecimal firstDecimal(JsonNode output, String... fields) {
		for (String field : fields) {
			BigDecimal value = decimal(output, field);
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private static String firstText(JsonNode output, String... fields) {
		for (String field : fields) {
			String value = text(output, field);
			if (!value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private static BigDecimal decimal(JsonNode output, String field) {
		String value = normalizeNumeric(text(output, field));
		if (value.isBlank() || "-".equals(value)) {
			return null;
		}
		try {
			return new BigDecimal(value);
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static long longValue(JsonNode output, String field, long fallback) {
		String value = normalizeNumeric(text(output, field));
		if (value.isBlank() || "-".equals(value)) {
			return fallback;
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static String text(JsonNode output, String field) {
		if (output == null) {
			return "";
		}
		JsonNode fieldNode = output.path(field);
		if (fieldNode.isMissingNode() || fieldNode.isNull()) {
			return "";
		}
		String value = fieldNode.stringValue();
		return value == null ? "" : value.trim();
	}

	private static String normalizeNumeric(String value) {
		return value.replace(",", "").replace("%", "").trim();
	}
}
