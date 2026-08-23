package com.kmarket.navigator.backend.stock.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;
import com.kmarket.navigator.backend.stock.application.port.MarketRepository;
import com.kmarket.navigator.backend.stock.domain.ExchangeRateSnapshot;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPolicy;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPrediction;
import com.kmarket.navigator.backend.stock.domain.MarketDataStatus;
import com.kmarket.navigator.backend.stock.domain.MarketIndexSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketQuoteSnapshot;
import com.kmarket.navigator.backend.stock.domain.ScreenerQuery;
import com.kmarket.navigator.backend.stock.domain.ScreenerSort;
import com.kmarket.navigator.backend.stock.domain.StockIdentity;
import com.kmarket.navigator.backend.stock.domain.StockMarketView;

@Service
public class MarketService {

	private static final Duration LIVE_QUOTE_MAX_AGE = Duration.ofMinutes(2);
	private static final int FOREIGN_HISTORY_LIMIT = 30;
	private static final Map<String, String> INDEX_NAMES = Map.of(
		"0001", "KOSPI",
		"1001", "KOSDAQ",
		"2001", "KOSPI 200"
	);
	private static final List<String> INDEX_ORDER = List.of("0001", "1001", "2001");
	private final MarketRepository repository;
	private final ForeignLimitPredictionEngine predictionEngine;
	private final Clock clock;

	@Autowired
	public MarketService(MarketRepository repository, ForeignLimitPredictionEngine predictionEngine) {
		this(repository, predictionEngine, Clock.systemUTC());
	}

	MarketService(
		MarketRepository repository,
		ForeignLimitPredictionEngine predictionEngine,
		Clock clock
	) {
		this.repository = repository;
		this.predictionEngine = predictionEngine;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<StockIdentity> searchStocks(String query, AuthenticatedUser user, int limit) {
		return repository.searchStocks(query, userId(user), limit);
	}

	@Transactional(readOnly = true)
	public List<StockMarketView> screenStocks(ScreenerQuery query, AuthenticatedUser user) {
		if (Boolean.TRUE.equals(query.watchlistOnly()) && user == null) {
			throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
		}
		if (query.minChangeRate() != null && query.maxChangeRate() != null
			&& query.minChangeRate().compareTo(query.maxChangeRate()) > 0) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return repository.findStocks(userId(user)).stream()
			.map(this::normalizeView)
			.filter(view -> matches(query, view))
			.sorted(comparator(query.sort()))
			.limit(query.limit())
			.toList();
	}

	@Transactional(readOnly = true)
	public MarketStockDetail stockDetail(String stockCode, AuthenticatedUser user) {
		StockMarketView view = normalizeView(repository.findStock(normalizeStockCode(stockCode), userId(user))
			.orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_STOCK)));
		ExchangeRateSnapshot rate = repository.findExchangeRate("USD").orElse(null);
		BigDecimal usd = view.quote() == null || rate == null
			? null
			: view.quote().currentPriceKrw()
				.divide(rate.krwPerUnit(), 2, RoundingMode.HALF_UP);
		ForeignLimitPolicy policy = policyByCode().get(view.stock().stockCode());
		ForeignLimitPrediction prediction = policy == null
			? null
			: predict(view.stock().securityId());
		return new MarketStockDetail(view, usd, rate, policy, prediction);
	}

	@Transactional(readOnly = true)
	public List<ForeignLimitMonitor> foreignLimitMonitors(AuthenticatedUser user) {
		Map<String, StockMarketView> views = new LinkedHashMap<>();
		for (StockMarketView view : repository.findStocks(userId(user))) {
			views.put(view.stock().stockCode(), normalizeView(view));
		}
		List<ForeignLimitMonitor> result = new ArrayList<>();
		for (ForeignLimitPolicy policy : repository.findForeignLimitPolicies()) {
			StockMarketView view = views.get(policy.stockCode());
			if (view == null) {
				continue;
			}
			boolean warning = view.foreignOwnership() != null
				&& view.foreignOwnership().limitExhaustionRate() != null
				&& view.foreignOwnership().limitExhaustionRate()
					.compareTo(policy.warningThreshold()) >= 0;
			result.add(new ForeignLimitMonitor(
				view,
				policy,
				warning,
				predict(view.stock().securityId())
			));
		}
		return List.copyOf(result);
	}

	@Transactional(readOnly = true)
	public List<MarketIndexSnapshot> marketIndices() {
		Map<String, MarketIndexSnapshot> snapshots = repository.findMarketIndices().stream()
			.collect(java.util.stream.Collectors.toMap(MarketIndexSnapshot::indexCode, value -> value));
		return INDEX_ORDER.stream()
			.map(code -> snapshots.containsKey(code)
				? normalizeIndex(snapshots.get(code))
				: new MarketIndexSnapshot(
					code,
					INDEX_NAMES.get(code),
					null,
					null,
					null,
					null,
					MarketDataStatus.UNAVAILABLE,
					null,
					"UNAVAILABLE"
				))
			.toList();
	}

	@Transactional(readOnly = true)
	public MarketHistory history(
		String stockCode,
		LocalDate from,
		LocalDate to,
		int limit
	) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
		}
		StockMarketView view = repository.findStock(normalizeStockCode(stockCode), null)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_STOCK));
		var prices = repository.findDailyPrices(view.stock().securityId(), from, to, limit);
		return new MarketHistory(
			view.stock().stockCode(),
			prices.isEmpty() ? MarketDataStatus.UNAVAILABLE : MarketDataStatus.CLOSED,
			prices
		);
	}

	private ForeignLimitPrediction predict(UUID securityId) {
		return predictionEngine.predict(
			repository.findForeignOwnershipHistory(securityId, FOREIGN_HISTORY_LIMIT)
		).orElse(null);
	}

	private Map<String, ForeignLimitPolicy> policyByCode() {
		return repository.findForeignLimitPolicies().stream()
			.collect(java.util.stream.Collectors.toMap(ForeignLimitPolicy::stockCode, value -> value));
	}

	private boolean matches(ScreenerQuery query, StockMarketView view) {
		if (query.market() != null && !view.stock().market().equalsIgnoreCase(query.market())) {
			return false;
		}
		if (query.sector() != null && !view.stock().sector().equalsIgnoreCase(query.sector())) {
			return false;
		}
		if (Boolean.TRUE.equals(query.watchlistOnly()) && !view.stock().watchlisted()) {
			return false;
		}
		if (query.tradingCaution() != null) {
			boolean caution = view.quote() != null && view.quote().tradingCaution();
			if (caution != query.tradingCaution()) {
				return false;
			}
		}
		if (query.minChangeRate() != null
			&& (view.quote() == null
				|| view.quote().changeRate().compareTo(query.minChangeRate()) < 0)) {
			return false;
		}
		return query.maxChangeRate() == null
			|| (view.quote() != null
				&& view.quote().changeRate().compareTo(query.maxChangeRate()) <= 0);
	}

	private Comparator<StockMarketView> comparator(ScreenerSort sort) {
		return switch (sort) {
			case NAME -> Comparator.comparing(
				(StockMarketView view) -> displayName(view.stock()),
				String.CASE_INSENSITIVE_ORDER
			).thenComparing(view -> view.stock().stockCode());
			case CHANGE_DESC -> Comparator.comparing(
				(StockMarketView view) -> view.quote() == null ? null : view.quote().changeRate(),
				Comparator.nullsLast(Comparator.reverseOrder())
			).thenComparing(view -> view.stock().stockCode());
			case CHANGE_ASC -> Comparator.comparing(
				(StockMarketView view) -> view.quote() == null ? null : view.quote().changeRate(),
				Comparator.nullsLast(Comparator.naturalOrder())
			).thenComparing(view -> view.stock().stockCode());
			case VOLUME_DESC -> Comparator.comparing(
				(StockMarketView view) -> view.quote() == null ? null : view.quote().volume(),
				Comparator.nullsLast(Comparator.reverseOrder())
			).thenComparing(view -> view.stock().stockCode());
			case STOCK_CODE -> Comparator.comparing(view -> view.stock().stockCode());
		};
	}

	private String displayName(StockIdentity stock) {
		return stock.nameEn().isBlank() ? stock.nameKo() : stock.nameEn();
	}

	private StockMarketView normalizeView(StockMarketView view) {
		if (view == null || view.quote() == null) {
			return view;
		}
		MarketQuoteSnapshot quote = view.quote();
		if ((quote.dataStatus() == MarketDataStatus.LIVE
			|| quote.dataStatus() == MarketDataStatus.DELAYED)
			&& quote.asOf().plus(LIVE_QUOTE_MAX_AGE).isBefore(clock.instant())) {
			quote = withStatus(quote, MarketDataStatus.STALE);
		}
		return new StockMarketView(view.stock(), quote, view.foreignOwnership());
	}

	private MarketIndexSnapshot normalizeIndex(MarketIndexSnapshot snapshot) {
		if ((snapshot.dataStatus() == MarketDataStatus.LIVE
			|| snapshot.dataStatus() == MarketDataStatus.DELAYED)
			&& snapshot.asOf().plus(LIVE_QUOTE_MAX_AGE).isBefore(clock.instant())) {
			return new MarketIndexSnapshot(
				snapshot.indexCode(),
				snapshot.indexName(),
				snapshot.currentValue(),
				snapshot.changeAmount(),
				snapshot.changeRate(),
				snapshot.volume(),
				MarketDataStatus.STALE,
				snapshot.asOf(),
				snapshot.source()
			);
		}
		return snapshot;
	}

	private MarketQuoteSnapshot withStatus(MarketQuoteSnapshot quote, MarketDataStatus status) {
		return new MarketQuoteSnapshot(
			quote.currentPriceKrw(),
			quote.changeAmountKrw(),
			quote.changeRate(),
			quote.openPriceKrw(),
			quote.highPriceKrw(),
			quote.lowPriceKrw(),
			quote.volume(),
			quote.marketSession(),
			quote.viActive(),
			quote.singlePriceTrading(),
			quote.priceLimitState(),
			quote.tradingHalted(),
			quote.tradingHaltReason(),
			quote.statusAvailable(),
			status,
			quote.asOf(),
			quote.source()
		);
	}

	private UUID userId(AuthenticatedUser user) {
		return user == null ? null : user.id();
	}

	private String normalizeStockCode(String stockCode) {
		return stockCode.trim().toUpperCase(Locale.ROOT);
	}
}
