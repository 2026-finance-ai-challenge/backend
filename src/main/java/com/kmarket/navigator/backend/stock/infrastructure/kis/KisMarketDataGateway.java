package com.kmarket.navigator.backend.stock.infrastructure.kis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.kmarket.navigator.backend.stock.application.port.MarketDataGateway;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketDataStatus;
import com.kmarket.navigator.backend.stock.domain.MarketDailyPrice;
import com.kmarket.navigator.backend.stock.domain.MarketForeignNetFlow;
import com.kmarket.navigator.backend.stock.domain.MarketIndexSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketIntradayPrice;
import com.kmarket.navigator.backend.stock.domain.MarketQuoteSnapshot;
import com.kmarket.navigator.backend.stock.domain.PriceLimitState;

import tools.jackson.databind.JsonNode;

@Component
class KisMarketDataGateway implements MarketDataGateway {

	private static final String QUOTE_TRANSACTION_ID = "FHKST01010100";
	private static final String INDEX_TRANSACTION_ID = "FHPUP02100000";
	private static final String DAILY_PRICE_TRANSACTION_ID = "FHKST03010100";
	private static final String FOREIGN_FLOW_TRANSACTION_ID = "FHPTJ04040000";
	private static final String TODAY_MINUTE_TRANSACTION_ID = "FHKST03010200";
	private static final String HISTORICAL_MINUTE_TRANSACTION_ID = "FHKST03010230";
	private static final DateTimeFormatter KIS_TIME = DateTimeFormatter.ofPattern("HHmmss");
	private static final DateTimeFormatter KIS_DATE = DateTimeFormatter.BASIC_ISO_DATE;
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
	private final Map<String, CachedIntradayPrices> intradayCache = new ConcurrentHashMap<>();

	@Autowired
	KisMarketDataGateway(
		@Qualifier("kisMarketRestClient") RestClient restClient,
		KisMarketProperties properties,
		KisAccessTokenProvider tokenProvider,
		KisCircuitBreaker circuitBreaker
	) {
		this(restClient, properties, tokenProvider, circuitBreaker, Clock.system(KOREA_ZONE));
	}

	KisMarketDataGateway(RestClient restClient, KisMarketProperties properties,
		KisAccessTokenProvider tokenProvider, KisCircuitBreaker circuitBreaker, Clock clock) {
		this.restClient = restClient;
		this.properties = properties;
		this.tokenProvider = tokenProvider;
		this.circuitBreaker = circuitBreaker;
		this.clock = clock.withZone(KOREA_ZONE);
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
	public Optional<ForeignOwnershipSnapshot> fetchForeignOwnership(String stockCode) {
		if (!configured()) {
			return Optional.empty();
		}
		JsonNode output = request(
			"/uapi/domestic-stock/v1/quotations/inquire-price",
			QUOTE_TRANSACTION_ID,
			"J",
			stockCode
		);
		Long foreignOwned = nullableLong(output, "frgn_hldn_qty");
		Long totalListed = nullableLong(output, "lstn_stcn");
		BigDecimal limitExhaustionRate = decimal(output, "hts_frgn_ehrt");
		if (foreignOwned == null || totalListed == null || totalListed <= 0
			|| limitExhaustionRate == null || limitExhaustionRate.signum() <= 0) {
			return Optional.empty();
		}
		BigDecimal ownershipRate = BigDecimal.valueOf(foreignOwned)
			.multiply(BigDecimal.valueOf(100))
			.divide(BigDecimal.valueOf(totalListed), 4, RoundingMode.HALF_UP);
		long foreignLimit = BigDecimal.valueOf(foreignOwned)
			.multiply(BigDecimal.valueOf(100))
			.divide(limitExhaustionRate, 0, RoundingMode.HALF_UP)
			.longValueExact();
		return Optional.of(new ForeignOwnershipSnapshot(
			foreignOwned,
			totalListed,
			foreignLimit,
			Math.max(0L, foreignLimit - foreignOwned),
			ownershipRate,
			limitExhaustionRate,
			LocalDate.now(clock),
			clock.instant(),
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

	@Override
	public List<MarketDailyPrice> fetchDailyPrices(
		String stockCode,
		LocalDate from,
		LocalDate to
	) {
		if (!configured()) {
			return List.of();
		}
		Map<LocalDate, MarketDailyPrice> collected = new TreeMap<>();
		LocalDate pageEnd = to;
		for (int page = 0; page < 8 && !pageEnd.isBefore(from); page++) {
			List<MarketDailyPrice> prices = fetchDailyPricePage(stockCode, from, pageEnd);
			if (prices.isEmpty()) {
				break;
			}
			prices.forEach(price -> collected.put(price.tradingDate(), price));
			LocalDate oldest = prices.stream()
				.map(MarketDailyPrice::tradingDate)
				.min(LocalDate::compareTo)
				.orElse(from);
			if (!oldest.isAfter(from)) {
				break;
			}
			LocalDate nextEnd = oldest.minusDays(1);
			if (!nextEnd.isBefore(pageEnd)) {
				break;
			}
			pageEnd = nextEnd;
			pauseBetweenRequests();
		}
		return List.copyOf(collected.values());
	}

	private List<MarketDailyPrice> fetchDailyPricePage(
		String stockCode,
		LocalDate from,
		LocalDate to
	) {
		Map<String, String> parameters = new LinkedHashMap<>();
		parameters.put("FID_COND_MRKT_DIV_CODE", "J");
		parameters.put("FID_INPUT_ISCD", stockCode);
		parameters.put("FID_INPUT_DATE_1", KIS_DATE.format(from));
		parameters.put("FID_INPUT_DATE_2", KIS_DATE.format(to));
		parameters.put("FID_PERIOD_DIV_CODE", "D");
		parameters.put("FID_ORG_ADJ_PRC", "1");
		JsonNode root = requestRoot(
			"/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice",
			DAILY_PRICE_TRANSACTION_ID,
			parameters
		);
		JsonNode output = root.path("output2");
		if (!output.isArray()) {
			return List.of();
		}
		List<MarketDailyPrice> prices = new ArrayList<>();
		for (JsonNode row : output) {
			LocalDate tradingDate = date(row, "stck_bsop_date");
			BigDecimal open = decimal(row, "stck_oprc");
			BigDecimal high = decimal(row, "stck_hgpr");
			BigDecimal low = decimal(row, "stck_lwpr");
			BigDecimal close = decimal(row, "stck_clpr");
			if (tradingDate == null || open == null || high == null || low == null || close == null) {
				continue;
			}
			prices.add(new MarketDailyPrice(
				tradingDate, open, high, low, close,
				longValue(row, "acml_vol", 0L), "KIS_REST_DAILY_PRICE"
			));
		}
		return List.copyOf(prices);
	}

	@Override
	public List<MarketForeignNetFlow> fetchForeignNetFlows(LocalDate tradingDate) {
		if (!configured()) {
			return List.of();
		}
		List<MarketForeignNetFlow> flows = new ArrayList<>();
		for (MarketFlowRequest market : List.of(
			new MarketFlowRequest("KOSPI", "0001", "KSP", "0001"),
			new MarketFlowRequest("KOSDAQ", "1001", "KSQ", "1001")
		)) {
			Map<String, String> parameters = new LinkedHashMap<>();
			parameters.put("FID_COND_MRKT_DIV_CODE", "U");
			parameters.put("FID_INPUT_ISCD", market.indexCode());
			parameters.put("FID_INPUT_DATE_1", KIS_DATE.format(tradingDate));
			parameters.put("FID_INPUT_ISCD_1", market.marketInputCode());
			parameters.put("FID_INPUT_DATE_2", KIS_DATE.format(tradingDate));
			parameters.put("FID_INPUT_ISCD_2", market.secondaryCode());
			JsonNode root = requestRoot(
				"/uapi/domestic-stock/v1/quotations/inquire-investor-daily-by-market",
				FOREIGN_FLOW_TRANSACTION_ID,
				parameters
			);
			JsonNode output = root.path("output");
			if (!output.isArray()) {
				continue;
			}
			for (JsonNode row : output) {
				LocalDate rowDate = date(row, "stck_bsop_date");
				BigDecimal amountMillions = decimal(row, "frgn_ntby_tr_pbmn");
				if (rowDate == null || amountMillions == null) {
					continue;
				}
				flows.add(new MarketForeignNetFlow(
					market.marketCode(), rowDate,
					amountMillions.multiply(BigDecimal.valueOf(1_000_000L)),
					clock.instant(), "KIS_REST_INVESTOR_DAILY_BY_MARKET"
				));
			}
		}
		return List.copyOf(flows);
	}

	@Override
	public List<MarketIntradayPrice> fetchIntradayPrices(String stockCode, LocalDate from, LocalDate to) {
		if (!configured()) return List.of();
		List<MarketIntradayPrice> prices = new ArrayList<>();
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
			prices.addAll(cachedMinutePricesForDate(stockCode, date));
		}
		return prices.stream().sorted(java.util.Comparator.comparing(MarketIntradayPrice::timestamp)).toList();
	}

	private List<MarketIntradayPrice> cachedMinutePricesForDate(String stockCode, LocalDate tradingDate) {
		String key = stockCode + ":" + tradingDate;
		Instant now = clock.instant();
		CachedIntradayPrices cached = intradayCache.get(key);
		if (cached != null && cached.expiresAt().isAfter(now)) return cached.items();
		List<MarketIntradayPrice> fresh = fetchMinutePricesForDate(stockCode, tradingDate);
		Duration ttl = tradingDate.equals(LocalDate.now(clock)) ? Duration.ofSeconds(30) : Duration.ofHours(12);
		intradayCache.put(key, new CachedIntradayPrices(fresh, now.plus(ttl)));
		if (intradayCache.size() > 1_000) {
			intradayCache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
		}
		return fresh;
	}

	private List<MarketIntradayPrice> fetchMinutePricesForDate(String stockCode, LocalDate tradingDate) {
		boolean today = tradingDate.equals(LocalDate.now(clock));
		LocalTime now = LocalTime.now(clock);
		LocalTime marketClose = LocalTime.of(15, 30);
		String cursor = KIS_TIME.format(today && now.isBefore(marketClose) ? now : marketClose);
		Map<Instant, MarketIntradayPrice> collected = new TreeMap<>();
		for (int page = 0; page < 14; page++) {
			Map<String, String> parameters = new LinkedHashMap<>();
			parameters.put("FID_COND_MRKT_DIV_CODE", "J");
			parameters.put("FID_INPUT_ISCD", stockCode);
			parameters.put("FID_INPUT_HOUR_1", cursor);
			parameters.put("FID_PW_DATA_INCU_YN", "Y");
			if (today) parameters.put("FID_ETC_CLS_CODE", "");
			else {
				parameters.put("FID_INPUT_DATE_1", KIS_DATE.format(tradingDate));
				parameters.put("FID_FAKE_TICK_INCU_YN", "");
			}
			JsonNode rows = requestRoot(
				today ? "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice"
					: "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice",
				today ? TODAY_MINUTE_TRANSACTION_ID : HISTORICAL_MINUTE_TRANSACTION_ID,
				parameters
			).path("output2");
			if (!rows.isArray() || rows.isEmpty()) break;
			LocalTime earliest = null;
			for (JsonNode row : rows) {
				LocalDate date = date(row, "stck_bsop_date");
				if (date == null) date = tradingDate;
				String timeText = text(row, "stck_cntg_hour");
				if (timeText == null || timeText.length() < 6) continue;
				LocalTime time;
				try { time = LocalTime.parse(timeText.substring(0, 6), KIS_TIME); }
				catch (RuntimeException exception) { continue; }
				if (time.isBefore(LocalTime.of(9, 0)) || time.isAfter(LocalTime.of(15, 30))) continue;
				BigDecimal close = firstDecimal(row, "stck_prpr", "stck_clpr");
				if (close == null) continue;
				Instant timestamp = LocalDateTime.of(date, time).atZone(KOREA_ZONE).toInstant();
				collected.put(timestamp, new MarketIntradayPrice(
					timestamp,
					valueOr(firstDecimal(row, "stck_oprc"), close),
					valueOr(firstDecimal(row, "stck_hgpr"), close),
					valueOr(firstDecimal(row, "stck_lwpr"), close),
					close,
					longValue(row, "cntg_vol", 0L),
					"KIS_REST_MINUTE_PRICE"
				));
				if (earliest == null || time.isBefore(earliest)) earliest = time;
			}
			if (earliest == null || !earliest.isAfter(LocalTime.of(9, 0))) break;
			String nextCursor = KIS_TIME.format(earliest.minusSeconds(1));
			if (nextCursor.equals(cursor)) break;
			cursor = nextCursor;
			pauseBetweenRequests();
		}
		return List.copyOf(collected.values());
	}

	private static BigDecimal valueOr(BigDecimal value, BigDecimal fallback) {
		return value == null ? fallback : value;
	}

	private record CachedIntradayPrices(List<MarketIntradayPrice> items, Instant expiresAt) {
	}

	private JsonNode request(String path, String transactionId, String marketCode, String code) {
		Map<String, String> parameters = new LinkedHashMap<>();
		parameters.put("FID_COND_MRKT_DIV_CODE", marketCode);
		parameters.put("FID_INPUT_ISCD", code);
		JsonNode root = requestRoot(path, transactionId, parameters);
		JsonNode output = root.path("output");
		if (output.isMissingNode() || output.isNull()) {
			throw new KisProviderException("KIS market response has no output");
		}
		return output;
	}

	private JsonNode requestRoot(String path, String transactionId, Map<String, String> parameters) {
		String token = tokenProvider.accessToken();
		JsonNode root = circuitBreaker.execute(() -> requestWithRetry(
			path, transactionId, parameters, token
		));
		if (root == null || !"0".equals(text(root, "rt_cd"))) {
			throw new KisProviderException("KIS market response was rejected");
		}
		return root;
	}

	private JsonNode requestWithRetry(
		String path,
		String transactionId,
		Map<String, String> parameters,
		String token
	) {
		int maxAttempts = Math.max(1, properties.getRetryMaxAttempts());
		for (int attempt = 1; ; attempt++) {
			try {
				return exchange(path, transactionId, parameters, token);
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
		Map<String, String> parameters,
		String token
	) {
		return restClient.get()
			.uri(uriBuilder -> {
				uriBuilder.path(path);
				parameters.forEach(uriBuilder::queryParam);
				return uriBuilder.build();
			})
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

	private void pauseBetweenRequests() {
		Duration delay = nonNegative(properties.getCollectionDelay());
		if (delay.isZero()) {
			return;
		}
		try {
			Thread.sleep(delay);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new KisProviderException("KIS market request was interrupted", exception);
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
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), KOREA_ZONE);
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

	private static LocalDate date(JsonNode output, String field) {
		String value = text(output, field);
		if (value.length() != 8) {
			return null;
		}
		try {
			return LocalDate.parse(value, KIS_DATE);
		} catch (java.time.format.DateTimeParseException exception) {
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

	private static Long nullableLong(JsonNode output, String field) {
		String value = normalizeNumeric(text(output, field));
		if (value.isBlank() || "-".equals(value)) {
			return null;
		}
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException exception) {
			return null;
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

	private record MarketFlowRequest(
		String marketCode,
		String indexCode,
		String marketInputCode,
		String secondaryCode
	) {
	}
}
