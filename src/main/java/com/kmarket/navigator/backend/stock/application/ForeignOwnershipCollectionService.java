package com.kmarket.navigator.backend.stock.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.stock.application.port.ForeignOwnershipGateway;
import com.kmarket.navigator.backend.stock.application.port.ForeignLimitPredictionGateway;
import com.kmarket.navigator.backend.stock.application.port.MarketDataGateway;
import com.kmarket.navigator.backend.stock.application.port.MarketSnapshotRepository;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitCollectionTarget;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketDailyPrice;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
public class ForeignOwnershipCollectionService {

	private static final Logger log = LoggerFactory.getLogger(ForeignOwnershipCollectionService.class);
	private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
	private static final int HISTORY_DAYS = 45;
	private static final int PREDICTION_HISTORY_LIMIT = 120;
	private final ForeignOwnershipGateway gateway;
	private final MarketDataGateway marketDataGateway;
	private final MarketSnapshotRepository repository;
	private final ForeignLimitPredictionGateway predictionGateway;
	private final Clock clock;

	@Autowired
	public ForeignOwnershipCollectionService(
		ForeignOwnershipGateway gateway,
		MarketDataGateway marketDataGateway,
		MarketSnapshotRepository repository,
		ForeignLimitPredictionGateway predictionGateway
	) {
		this(gateway, marketDataGateway, repository, predictionGateway, Clock.system(KOREA_ZONE));
	}

	ForeignOwnershipCollectionService(
		ForeignOwnershipGateway gateway,
		MarketDataGateway marketDataGateway,
		MarketSnapshotRepository repository,
		ForeignLimitPredictionGateway predictionGateway,
		Clock clock
	) {
		this.gateway = gateway;
		this.marketDataGateway = marketDataGateway;
		this.repository = repository;
		this.predictionGateway = predictionGateway;
		this.clock = clock;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void collectAfterStartup() {
		collect();
	}

	@Scheduled(cron = "${kmarket.market.krx.collection-cron:0 30 18 * * MON-FRI}", zone = "Asia/Seoul")
	@SchedulerLock(
		name = "foreign-ownership-collection",
		lockAtMostFor = "PT10M",
		lockAtLeastFor = "PT1S"
	)
	public void collect() {
		LocalDate to = LocalDate.now(clock);
		LocalDate from = to.minusDays(HISTORY_DAYS);
		for (ForeignLimitCollectionTarget target : repository.findForeignLimitTargets()) {
			try {
				if (gateway.configured()) {
					gateway.fetchHistory(target, from, to)
						.forEach(snapshot -> repository.saveForeignOwnership(target.stockCode(), snapshot));
				} else if (marketDataGateway.configured()) {
					marketDataGateway.fetchForeignOwnership(target.stockCode())
						.flatMap(snapshot -> withLatestTradingDate(target.stockCode(), snapshot, to))
						.ifPresent(snapshot -> repository.saveForeignOwnership(target.stockCode(), snapshot));
				}
				precomputePrediction(target.stockCode());
			} catch (RuntimeException exception) {
				log.warn(
					"Foreign ownership collection failed for stockCode={} type={}",
					target.stockCode(),
					exception.getClass().getSimpleName()
				);
			}
		}
	}

	@Scheduled(cron = "${kmarket.market.foreign-prediction-cron:0 40 18 * * MON-FRI}", zone = "Asia/Seoul")
	@SchedulerLock(
		name = "foreign-ownership-prediction",
		lockAtMostFor = "PT10M",
		lockAtLeastFor = "PT1S"
	)
	public void precomputeAllPredictions() {
		for (ForeignLimitCollectionTarget target : repository.findForeignLimitTargets()) {
			try {
				precomputePrediction(target.stockCode());
			}
			catch (RuntimeException exception) {
				log.warn(
					"Foreign ownership prediction failed for stockCode={} type={}",
					target.stockCode(),
					exception.getClass().getSimpleName()
				);
			}
		}
	}

	private void precomputePrediction(String stockCode) {
		List<ForeignOwnershipSnapshot> history = repository.findForeignOwnershipHistory(
			stockCode,
			PREDICTION_HISTORY_LIMIT
		);
		predictionGateway.predict(stockCode, history)
			.ifPresent(prediction -> repository.saveForeignLimitPrediction(stockCode, prediction));
	}

	private Optional<ForeignOwnershipSnapshot>
		withLatestTradingDate(
			String stockCode,
			ForeignOwnershipSnapshot snapshot,
			LocalDate today
		) {
		return marketDataGateway.fetchDailyPrices(stockCode, today.minusDays(14), today).stream()
			.map(MarketDailyPrice::tradingDate)
			.max(Comparator.naturalOrder())
			.map(tradingDate -> new ForeignOwnershipSnapshot(
				snapshot.foreignOwnedQuantity(),
				snapshot.totalListedQuantity(),
				snapshot.foreignLimitQuantity(),
				snapshot.availableQuantity(),
				snapshot.ownershipRate(),
				snapshot.limitExhaustionRate(),
				tradingDate,
				snapshot.collectedAt(),
				snapshot.source()
			));
	}
}
