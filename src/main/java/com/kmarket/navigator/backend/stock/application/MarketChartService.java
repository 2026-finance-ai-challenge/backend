package com.kmarket.navigator.backend.stock.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.stock.application.port.MarketDataGateway;
import com.kmarket.navigator.backend.stock.domain.MarketDailyPrice;
import com.kmarket.navigator.backend.stock.domain.MarketDataStatus;
import com.kmarket.navigator.backend.stock.domain.MarketIntradayPrice;

@Service
public class MarketChartService {

	private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
	private static final Duration CACHE_TTL = Duration.ofSeconds(30);
	private final MarketService marketService;
	private final MarketDataGateway marketDataGateway;
	private final Map<String, CachedChart> cache = new ConcurrentHashMap<>();

	public MarketChartService(MarketService marketService, MarketDataGateway marketDataGateway) {
		this.marketService = marketService;
		this.marketDataGateway = marketDataGateway;
	}

	public MarketChart chart(String stockCode, MarketChartPeriod period) {
		String normalized = stockCode.strip().toUpperCase(java.util.Locale.ROOT);
		String cacheKey = normalized + ":" + period;
		CachedChart cached = cache.get(cacheKey);
		if (cached != null && cached.createdAt().plus(CACHE_TTL).isAfter(Instant.now())) return cached.chart();
		MarketChart chart = switch (period) {
			case ONE_DAY -> oneDay(normalized);
			case ONE_WEEK -> intraday(normalized, period, LocalDate.now(KOREA_ZONE).minusDays(6), 60);
			case ONE_MONTH -> daily(normalized, period, 31);
			case THREE_MONTH -> daily(normalized, period, 93);
			case ONE_YEAR -> daily(normalized, period, 366);
		};
		cache.put(cacheKey, new CachedChart(chart, Instant.now()));
		return chart;
	}

	private MarketChart oneDay(String stockCode) {
		LocalDate to = LocalDate.now(KOREA_ZONE);
		marketService.history(stockCode, to.minusDays(1), to, 2);
		List<MarketIntradayPrice> raw = marketDataGateway.fetchIntradayPrices(stockCode, to.minusDays(7), to);
		LocalDate latest = raw.stream()
			.map(price -> price.timestamp().atZone(KOREA_ZONE).toLocalDate())
			.max(LocalDate::compareTo)
			.orElse(null);
		List<MarketIntradayPrice> latestSession = latest == null ? List.of() : raw.stream()
			.filter(price -> price.timestamp().atZone(KOREA_ZONE).toLocalDate().equals(latest))
			.toList();
		return intradayChart(stockCode, MarketChartPeriod.ONE_DAY, 10, latestSession);
	}

	private MarketChart intraday(String stockCode, MarketChartPeriod period, LocalDate from, int intervalMinutes) {
		LocalDate to = LocalDate.now(KOREA_ZONE);
		// 지원 종목 검증은 일별 이력 조회의 동일 정책을 사용한다.
		marketService.history(stockCode, to.minusDays(1), to, 2);
		List<MarketIntradayPrice> raw = marketDataGateway.fetchIntradayPrices(stockCode, from, to);
		return intradayChart(stockCode, period, intervalMinutes, raw);
	}

	private MarketChart intradayChart(
		String stockCode,
		MarketChartPeriod period,
		int intervalMinutes,
		List<MarketIntradayPrice> raw
	) {
		List<MarketChartBar> bars = aggregate(raw, intervalMinutes);
		return new MarketChart(
			stockCode,
			period,
			bars.isEmpty() ? MarketDataStatus.UNAVAILABLE : MarketDataStatus.DELAYED,
			intervalMinutes,
			bars,
			bars.isEmpty() ? "UNAVAILABLE" : "KIS_REST_MINUTE_PRICE"
		);
	}

	private MarketChart daily(String stockCode, MarketChartPeriod period, int days) {
		LocalDate to = LocalDate.now(KOREA_ZONE);
		MarketHistory history = marketService.history(stockCode, to.minusDays(days), to, days + 10);
		List<MarketChartBar> bars = history.items().stream().map(this::dailyBar).toList();
		return new MarketChart(stockCode, period, history.dataStatus(), 1_440, bars,
			bars.isEmpty() ? "UNAVAILABLE" : "KIS_REST_DAILY_PRICE");
	}

	private MarketChartBar dailyBar(MarketDailyPrice price) {
		Instant timestamp = price.tradingDate().atTime(LocalTime.of(15, 30)).atZone(KOREA_ZONE).toInstant();
		return new MarketChartBar(timestamp, price.openPriceKrw(), price.highPriceKrw(), price.lowPriceKrw(),
			price.closePriceKrw(), price.volume());
	}

	private List<MarketChartBar> aggregate(List<MarketIntradayPrice> raw, int intervalMinutes) {
		Map<Instant, MutableBar> buckets = new LinkedHashMap<>();
		for (MarketIntradayPrice price : raw) {
			ZonedDateTime local = price.timestamp().atZone(KOREA_ZONE);
			int minute = local.getMinute() / intervalMinutes * intervalMinutes;
			Instant bucket = local.withMinute(minute).withSecond(0).withNano(0).toInstant();
			buckets.computeIfAbsent(bucket, ignored -> new MutableBar(price)).accept(price);
		}
		List<MarketChartBar> result = new ArrayList<>();
		buckets.forEach((timestamp, bar) -> result.add(bar.toImmutable(timestamp)));
		return List.copyOf(result);
	}

	private static final class MutableBar {
		private final BigDecimal open;
		private BigDecimal high;
		private BigDecimal low;
		private BigDecimal close;
		private long volume;

		private MutableBar(MarketIntradayPrice price) {
			this.open = price.openPriceKrw();
			this.high = price.highPriceKrw();
			this.low = price.lowPriceKrw();
			this.close = price.closePriceKrw();
		}

		private void accept(MarketIntradayPrice price) {
			high = high.max(price.highPriceKrw());
			low = low.min(price.lowPriceKrw());
			close = price.closePriceKrw();
			volume += price.volume();
		}

		private MarketChartBar toImmutable(Instant timestamp) {
			return new MarketChartBar(timestamp, open, high, low, close, volume);
		}
	}

	private record CachedChart(MarketChart chart, Instant createdAt) {
	}

	public enum MarketChartPeriod {
		ONE_DAY,
		ONE_WEEK,
		ONE_MONTH,
		THREE_MONTH,
		ONE_YEAR
	}

	public record MarketChart(
		String stockCode,
		MarketChartPeriod period,
		MarketDataStatus status,
		int intervalMinutes,
		List<MarketChartBar> items,
		String source
	) {
	}

	public record MarketChartBar(
		Instant timestamp,
		BigDecimal openPriceKrw,
		BigDecimal highPriceKrw,
		BigDecimal lowPriceKrw,
		BigDecimal closePriceKrw,
		long volume
	) {
	}
}
