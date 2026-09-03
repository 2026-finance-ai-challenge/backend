package com.kmarket.navigator.backend.stock.presentation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;
import com.kmarket.navigator.backend.identity.infrastructure.ClientContextResolver;
import com.kmarket.navigator.backend.stock.application.ForeignLimitMonitor;
import com.kmarket.navigator.backend.stock.application.GlobalPeerService;
import com.kmarket.navigator.backend.stock.application.MarketHistory;
import com.kmarket.navigator.backend.stock.application.MarketChartService;
import com.kmarket.navigator.backend.stock.application.MarketChartService.MarketChart;
import com.kmarket.navigator.backend.stock.application.MarketChartService.MarketChartPeriod;
import com.kmarket.navigator.backend.stock.application.MarketService;
import com.kmarket.navigator.backend.stock.application.MarketStockDetail;
import com.kmarket.navigator.backend.stock.domain.ExchangeRateSnapshot;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPolicy;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPrediction;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;
import com.kmarket.navigator.backend.stock.domain.GlobalPeerAnalysis;
import com.kmarket.navigator.backend.stock.domain.MarketDailyPrice;
import com.kmarket.navigator.backend.stock.domain.MarketDataStatus;
import com.kmarket.navigator.backend.stock.domain.MarketIndexSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketForeignNetFlowSummary;
import com.kmarket.navigator.backend.stock.domain.MarketQuoteSnapshot;
import com.kmarket.navigator.backend.stock.domain.PriceLimitState;
import com.kmarket.navigator.backend.stock.domain.ScreenerQuery;
import com.kmarket.navigator.backend.stock.domain.ScreenerSort;
import com.kmarket.navigator.backend.stock.domain.StockIdentity;
import com.kmarket.navigator.backend.stock.domain.StockMarketView;
import com.kmarket.navigator.backend.stock.infrastructure.kis.KisRealtimeMarketService;

@Validated
@RestController
@RequestMapping("/api/v1/market")
public class MarketController {

	private static final String STOCK_CODE_PATTERN = "^[0-9A-Za-z]{6}$";
	private final MarketService service;
	private final GlobalPeerService globalPeerService;
	private final ClientContextResolver clientContextResolver;
	private final MarketChartService chartService;
	private final KisRealtimeMarketService realtimeMarketService;

	public MarketController(
		MarketService service,
		GlobalPeerService globalPeerService,
		ClientContextResolver clientContextResolver,
		MarketChartService chartService,
		KisRealtimeMarketService realtimeMarketService
	) {
		this.service = service;
		this.globalPeerService = globalPeerService;
		this.clientContextResolver = clientContextResolver;
		this.chartService = chartService;
		this.realtimeMarketService = realtimeMarketService;
	}

	@GetMapping("/stocks/search")
	public ResponseEntity<SearchResponse> search(
		@RequestParam @NotBlank @Size(max = 40) String query,
		@RequestParam(defaultValue = "10") @Min(1) @Max(75) int limit,
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		List<SearchItem> items = service.searchStocks(query, user, limit).stream()
			.map(SearchItem::from)
			.toList();
		return noStore(new SearchResponse(query, items.size(), items));
	}

	@GetMapping("/stocks")
	public ResponseEntity<ScreenerResponse> screener(
		@RequestParam(required = false) @Pattern(regexp = "KOSPI|KOSDAQ") String market,
		@RequestParam(required = false) @Size(max = 120) String sector,
		@RequestParam(required = false) BigDecimal minChangeRate,
		@RequestParam(required = false) BigDecimal maxChangeRate,
		@RequestParam(required = false) @Min(0) Long minVolume,
		@RequestParam(required = false) @Min(0) Long maxVolume,
		@RequestParam(required = false) Boolean tradingCaution,
		@RequestParam(required = false) Boolean watchlist,
		@RequestParam(defaultValue = "STOCK_CODE") ScreenerSort sort,
		@RequestParam(defaultValue = "75") @Min(1) @Max(75) int limit,
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		var query = new ScreenerQuery(
			market,
			blankToNull(sector),
			minChangeRate,
			maxChangeRate,
			minVolume,
			maxVolume,
			tradingCaution,
			watchlist,
			sort,
			limit
		);
		List<StockCardResponse> items = service.screenStocks(query, user).stream()
			.map(StockCardResponse::from)
			.toList();
		return noStore(new ScreenerResponse(items.size(), items));
	}

	@GetMapping("/stocks/{stockCode}")
	public ResponseEntity<StockDetailResponse> stockDetail(
		@PathVariable @Pattern(regexp = STOCK_CODE_PATTERN) String stockCode,
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		return noStore(StockDetailResponse.from(service.stockDetail(stockCode, user)));
	}

	@GetMapping("/indices")
	public ResponseEntity<List<MarketIndexResponse>> indices() {
		return noStore(service.marketIndices().stream().map(MarketIndexResponse::from).toList());
	}

	@GetMapping("/exchange-rates/{currency}")
	public ResponseEntity<ExchangeRateResponse> exchangeRate(
		@PathVariable @Pattern(regexp = "USD") String currency
	) {
		return noStore(ExchangeRateResponse.from(service.exchangeRate(currency)));
	}

	@GetMapping("/foreign-limits")
	public ResponseEntity<List<ForeignLimitMonitorResponse>> foreignLimits(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		return noStore(service.foreignLimitMonitors(user).stream()
			.map(ForeignLimitMonitorResponse::from)
			.toList());
	}

	@GetMapping("/foreign-net-flow")
	public ResponseEntity<ForeignNetFlowResponse> foreignNetFlow() {
		return noStore(ForeignNetFlowResponse.from(service.foreignNetFlow()));
	}

	@GetMapping("/stocks/{stockCode}/history")
	public ResponseEntity<MarketHistoryResponse> history(
		@PathVariable @Pattern(regexp = STOCK_CODE_PATTERN) String stockCode,
		@RequestParam(required = false) LocalDate from,
		@RequestParam(required = false) LocalDate to,
		@RequestParam(defaultValue = "365") @Min(1) @Max(1250) int limit
	) {
		return noStore(MarketHistoryResponse.from(service.history(stockCode, from, to, limit)));
	}

	@GetMapping("/stocks/{stockCode}/chart")
	public ResponseEntity<MarketChart> chart(
		@PathVariable @Pattern(regexp = STOCK_CODE_PATTERN) String stockCode,
		@RequestParam(defaultValue = "1D") @Pattern(regexp = "1D|1W|1M|3M|1Y") String period
	) {
		MarketChartPeriod resolved = switch (period) {
			case "1D" -> MarketChartPeriod.ONE_DAY;
			case "1W" -> MarketChartPeriod.ONE_WEEK;
			case "3M" -> MarketChartPeriod.THREE_MONTH;
			case "1Y" -> MarketChartPeriod.ONE_YEAR;
			default -> MarketChartPeriod.ONE_MONTH;
		};
		return noStore(chartService.chart(stockCode, resolved));
	}

	@GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(
		@RequestParam(required = false) @Pattern(regexp = STOCK_CODE_PATTERN) String stockCode
	) {
		return realtimeMarketService.stream(stockCode);
	}

	@GetMapping("/stocks/{stockCode}/global-peers")
	public ResponseEntity<GlobalPeerAnalysis> globalPeers(
		@PathVariable @Pattern(regexp = STOCK_CODE_PATTERN) String stockCode,
		HttpServletRequest request
	) {
		return noStore(globalPeerService.analyze(
			stockCode,
			clientContextResolver.resolve(request).ipHash()
		));
	}

	private <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	public record SearchResponse(String query, int count, List<SearchItem> items) {
	}

	public record ForeignNetFlowResponse(
		LocalDate tradingDate,
		BigDecimal netPurchaseAmountKrw,
		int consecutiveDays,
		MarketDataStatus status,
		Instant asOf,
		String source
	) {
		static ForeignNetFlowResponse from(MarketForeignNetFlowSummary summary) {
			return summary == null
				? new ForeignNetFlowResponse(
					null, null, 0, MarketDataStatus.UNAVAILABLE, null, "UNAVAILABLE"
				)
				: new ForeignNetFlowResponse(
					summary.tradingDate(), summary.netPurchaseAmountKrw(),
					summary.consecutiveDays(), summary.dataStatus(), summary.asOf(), summary.source()
				);
		}
	}

	public record SearchItem(
		String stockCode,
		String nameKo,
		String nameEn,
		String market,
		String sector,
		boolean watchlisted
	) {
		static SearchItem from(StockIdentity stock) {
			return new SearchItem(
				stock.stockCode(),
				stock.nameKo(),
				stock.nameEn(),
				stock.market(),
				stock.sector(),
				stock.watchlisted()
			);
		}
	}

	public record ScreenerResponse(int count, List<StockCardResponse> items) {
	}

	public record StockCardResponse(
		String stockCode,
		String nameKo,
		String nameEn,
		String market,
		String sector,
		boolean watchlisted,
		QuoteResponse quote,
		ForeignOwnershipResponse foreignOwnership
	) {
		static StockCardResponse from(StockMarketView view) {
			return new StockCardResponse(
				view.stock().stockCode(),
				view.stock().nameKo(),
				view.stock().nameEn(),
				view.stock().market(),
				view.stock().sector(),
				view.stock().watchlisted(),
				QuoteResponse.from(view.quote()),
				ForeignOwnershipResponse.from(view.foreignOwnership())
			);
		}
	}

	public record StockDetailResponse(
		String stockCode,
		String nameKo,
		String nameEn,
		String market,
		String sector,
		boolean watchlisted,
		QuoteResponse quote,
		BigDecimal currentPriceUsd,
		ExchangeRateResponse exchangeRate,
		ForeignOwnershipResponse foreignOwnership,
		boolean subjectToForeignAcquisitionLimit,
		ForeignLimitPolicyResponse foreignLimitPolicy,
		ForeignLimitPredictionResponse foreignLimitPrediction
	) {
		static StockDetailResponse from(MarketStockDetail detail) {
			StockMarketView view = detail.view();
			return new StockDetailResponse(
				view.stock().stockCode(),
				view.stock().nameKo(),
				view.stock().nameEn(),
				view.stock().market(),
				view.stock().sector(),
				view.stock().watchlisted(),
				QuoteResponse.from(view.quote()),
				detail.currentPriceUsd(),
				ExchangeRateResponse.from(detail.usdExchangeRate()),
				ForeignOwnershipResponse.from(view.foreignOwnership()),
				detail.foreignLimitPolicy() != null,
				ForeignLimitPolicyResponse.from(detail.foreignLimitPolicy()),
				ForeignLimitPredictionResponse.from(
					detail.foreignLimitPrediction(),
					view.quote() == null ? null : view.quote().marketSession(),
					detail.foreignLimitPolicy() != null
				)
			);
		}
	}

	public record QuoteResponse(
		MarketDataStatus status,
		BigDecimal currentPriceKrw,
		BigDecimal changeAmountKrw,
		BigDecimal changeRate,
		BigDecimal openPriceKrw,
		BigDecimal highPriceKrw,
		BigDecimal lowPriceKrw,
		Long volume,
		String marketSession,
		Boolean viActive,
		Boolean singlePriceTrading,
		PriceLimitState priceLimitState,
		Boolean tradingHalted,
		String tradingHaltReason,
		boolean tradingStatusAvailable,
		Instant asOf,
		String source
	) {
		static QuoteResponse from(MarketQuoteSnapshot quote) {
			if (quote == null) {
				return new QuoteResponse(
					MarketDataStatus.UNAVAILABLE,
					null, null, null, null, null, null, null, null,
					null, null, null, null, null, false, null, "UNAVAILABLE"
				);
			}
			return new QuoteResponse(
				quote.dataStatus(),
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
				quote.asOf(),
				quote.source()
			);
		}
	}

	public record ExchangeRateResponse(
		String currency,
		BigDecimal krwPerUnit,
		MarketDataStatus status,
		Instant asOf,
		String source
	) {
		static ExchangeRateResponse from(ExchangeRateSnapshot rate) {
			return rate == null
				? new ExchangeRateResponse("USD", null, MarketDataStatus.UNAVAILABLE, null, "UNAVAILABLE")
				: new ExchangeRateResponse(
					rate.currency(),
					rate.krwPerUnit(),
					rate.dataStatus(),
					rate.asOf(),
					rate.source()
				);
		}
	}

	public record ForeignOwnershipResponse(
		String status,
		Long foreignOwnedQuantity,
		Long totalListedQuantity,
		Long foreignLimitQuantity,
		Long availableQuantity,
		BigDecimal ownershipRate,
		BigDecimal limitExhaustionRate,
		LocalDate baseDate,
		Instant collectedAt,
		String source
	) {
		static ForeignOwnershipResponse from(ForeignOwnershipSnapshot ownership) {
			return ownership == null
				? new ForeignOwnershipResponse(
					"UNAVAILABLE", null, null, null, null, null, null, null, null, "UNAVAILABLE"
				)
				: new ForeignOwnershipResponse(
					"AVAILABLE",
					ownership.foreignOwnedQuantity(),
					ownership.totalListedQuantity(),
					ownership.foreignLimitQuantity(),
					ownership.availableQuantity(),
					ownership.ownershipRate(),
					ownership.limitExhaustionRate(),
					ownership.baseDate(),
					ownership.collectedAt(),
					ownership.source()
				);
		}
	}

	public record ForeignLimitPolicyResponse(
		BigDecimal warningThreshold,
		LocalDate effectiveFrom
	) {
		static ForeignLimitPolicyResponse from(ForeignLimitPolicy policy) {
			return policy == null
				? null
				: new ForeignLimitPolicyResponse(policy.warningThreshold(), policy.effectiveFrom());
		}
	}

	public record ForeignLimitPredictionResponse(
		String status,
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
		static ForeignLimitPredictionResponse from(
			ForeignLimitPrediction prediction,
			String marketSession,
			boolean applicable
		) {
			if (!applicable) {
				return new ForeignLimitPredictionResponse(
					"NOT_APPLICABLE", null, null, null, 0, 0, null, null, null, null,
					"NOT_APPLICABLE"
				);
			}
			if (prediction == null) {
				return new ForeignLimitPredictionResponse(
					"REGULAR".equals(marketSession) ? "UNAVAILABLE" : "MARKET_CLOSED",
					null, null, null, 0, 0, null, null, null, null,
					"REGULAR".equals(marketSession) ? "UNAVAILABLE" : "MARKET_CLOSED"
				);
			}
			if (!"REGULAR".equals(marketSession)) {
				return new ForeignLimitPredictionResponse(
					"MARKET_CLOSED",
					null,
					null,
					null,
					prediction.observationCount(),
					prediction.observationWindowDays(),
					prediction.confidence(),
					prediction.modelVersion(),
					prediction.baseDate(),
					prediction.calculatedAt(),
					prediction.source()
				);
			}
			return new ForeignLimitPredictionResponse(
					"AVAILABLE",
					prediction.minRate(),
					prediction.baseRate(),
					prediction.maxRate(),
					prediction.observationCount(),
					prediction.observationWindowDays(),
					prediction.confidence(),
					prediction.modelVersion(),
					prediction.baseDate(),
					prediction.calculatedAt(),
					prediction.source()
				);
		}
	}

	public record ForeignLimitMonitorResponse(
		StockCardResponse stock,
		ForeignLimitPolicyResponse policy,
		boolean warning,
		ForeignLimitPredictionResponse prediction
	) {
		static ForeignLimitMonitorResponse from(ForeignLimitMonitor monitor) {
			return new ForeignLimitMonitorResponse(
				StockCardResponse.from(monitor.view()),
				ForeignLimitPolicyResponse.from(monitor.policy()),
				monitor.warning(),
				ForeignLimitPredictionResponse.from(
					monitor.prediction(),
					monitor.view().quote() == null ? null : monitor.view().quote().marketSession(),
					true
				)
			);
		}
	}

	public record MarketIndexResponse(
		String indexCode,
		String indexName,
		BigDecimal currentValue,
		BigDecimal changeAmount,
		BigDecimal changeRate,
		Long volume,
		MarketDataStatus status,
		Instant asOf,
		String source
	) {
		static MarketIndexResponse from(MarketIndexSnapshot snapshot) {
			return new MarketIndexResponse(
				snapshot.indexCode(),
				snapshot.indexName(),
				snapshot.currentValue(),
				snapshot.changeAmount(),
				snapshot.changeRate(),
				snapshot.volume(),
				snapshot.dataStatus(),
				snapshot.asOf(),
				snapshot.source()
			);
		}
	}

	public record MarketHistoryResponse(
		String stockCode,
		MarketDataStatus status,
		List<DailyPriceResponse> items
	) {
		static MarketHistoryResponse from(MarketHistory history) {
			return new MarketHistoryResponse(
				history.stockCode(),
				history.dataStatus(),
				history.items().stream().map(DailyPriceResponse::from).toList()
			);
		}
	}

	public record DailyPriceResponse(
		LocalDate tradingDate,
		BigDecimal openPriceKrw,
		BigDecimal highPriceKrw,
		BigDecimal lowPriceKrw,
		BigDecimal closePriceKrw,
		long volume,
		String source
	) {
		static DailyPriceResponse from(MarketDailyPrice price) {
			return new DailyPriceResponse(
				price.tradingDate(),
				price.openPriceKrw(),
				price.highPriceKrw(),
				price.lowPriceKrw(),
				price.closePriceKrw(),
				price.volume(),
				price.source()
			);
		}
	}
}
