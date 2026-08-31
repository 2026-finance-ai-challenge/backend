package com.kmarket.navigator.backend.stock.infrastructure.persistence;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.stock.application.port.MarketSnapshotRepository;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitCollectionTarget;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;
import com.kmarket.navigator.backend.stock.domain.ExchangeRateSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketDailyPrice;
import com.kmarket.navigator.backend.stock.domain.MarketForeignNetFlow;
import com.kmarket.navigator.backend.stock.domain.MarketIndexSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketQuoteSnapshot;

@Repository
class JdbcMarketSnapshotRepository implements MarketSnapshotRepository {

	private final JdbcClient jdbcClient;

	JdbcMarketSnapshotRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public List<String> findSupportedStockCodes() {
		return jdbcClient.sql("""
			SELECT s.stock_code
			FROM security s
			JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			ORDER BY s.stock_code
			""")
			.query(String.class)
			.list();
	}

	@Override
	@Transactional
	public void saveQuote(String stockCode, MarketQuoteSnapshot quote) {
		JdbcClient.StatementSpec statement = jdbcClient.sql("""
			INSERT INTO market_quote_snapshot (
			    security_id, current_price_krw, change_amount_krw, change_rate,
			    open_price_krw, high_price_krw, low_price_krw, volume, market_session,
			    vi_active, single_price_trading, price_limit_state, trading_halted,
			    trading_halt_reason, status_available, data_status, as_of, received_at, source
			)
			SELECT s.id, :currentPrice, :changeAmount, :changeRate,
			       :openPrice, :highPrice, :lowPrice, :volume, :marketSession,
			       :viActive, :singlePriceTrading, :priceLimitState, :tradingHalted,
			       :tradingHaltReason, :statusAvailable, :dataStatus, :asOf,
			       CURRENT_TIMESTAMP, :source
			FROM security s
			JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			WHERE s.stock_code = :stockCode
			ON CONFLICT (security_id) DO UPDATE
			SET current_price_krw = EXCLUDED.current_price_krw,
			    change_amount_krw = EXCLUDED.change_amount_krw,
			    change_rate = EXCLUDED.change_rate,
			    open_price_krw = EXCLUDED.open_price_krw,
			    high_price_krw = EXCLUDED.high_price_krw,
			    low_price_krw = EXCLUDED.low_price_krw,
			    volume = EXCLUDED.volume,
			    market_session = EXCLUDED.market_session,
			    vi_active = EXCLUDED.vi_active,
			    single_price_trading = EXCLUDED.single_price_trading,
			    price_limit_state = EXCLUDED.price_limit_state,
			    trading_halted = EXCLUDED.trading_halted,
			    trading_halt_reason = EXCLUDED.trading_halt_reason,
			    status_available = EXCLUDED.status_available,
			    data_status = EXCLUDED.data_status,
			    as_of = EXCLUDED.as_of,
			    received_at = EXCLUDED.received_at,
			    source = EXCLUDED.source
			WHERE EXCLUDED.as_of >= market_quote_snapshot.as_of
			""")
			.param("stockCode", stockCode)
			.param("currentPrice", quote.currentPriceKrw())
			.param("changeAmount", quote.changeAmountKrw())
			.param("changeRate", quote.changeRate())
			.param("volume", quote.volume())
			.param("marketSession", quote.marketSession())
			.param("statusAvailable", quote.statusAvailable())
			.param("dataStatus", quote.dataStatus().name())
			.param("asOf", atUtc(quote.asOf()))
			.param("source", quote.source());
		statement = nullable(statement, "openPrice", quote.openPriceKrw(), Types.NUMERIC);
		statement = nullable(statement, "highPrice", quote.highPriceKrw(), Types.NUMERIC);
		statement = nullable(statement, "lowPrice", quote.lowPriceKrw(), Types.NUMERIC);
		statement = nullable(statement, "viActive", quote.viActive(), Types.BOOLEAN);
		statement = nullable(
			statement,
			"singlePriceTrading",
			quote.singlePriceTrading(),
			Types.BOOLEAN
		);
		statement = nullable(
			statement,
			"priceLimitState",
			quote.priceLimitState() == null ? null : quote.priceLimitState().name(),
			Types.VARCHAR
		);
		statement = nullable(statement, "tradingHalted", quote.tradingHalted(), Types.BOOLEAN);
		statement = nullable(
			statement,
			"tradingHaltReason",
			quote.tradingHaltReason(),
			Types.VARCHAR
		);
		statement.update();
	}

	@Override
	@Transactional
	public void saveIndex(MarketIndexSnapshot index) {
		JdbcClient.StatementSpec statement = jdbcClient.sql("""
			INSERT INTO market_index_snapshot (
			    index_code, index_name, current_value, change_amount, change_rate,
			    volume, data_status, as_of, received_at, source
			)
			VALUES (
			    :indexCode, :indexName, :currentValue, :changeAmount, :changeRate,
			    :volume, :dataStatus, :asOf, CURRENT_TIMESTAMP, :source
			)
			ON CONFLICT (index_code) DO UPDATE
			SET index_name = EXCLUDED.index_name,
			    current_value = EXCLUDED.current_value,
			    change_amount = EXCLUDED.change_amount,
			    change_rate = EXCLUDED.change_rate,
			    volume = EXCLUDED.volume,
			    data_status = EXCLUDED.data_status,
			    as_of = EXCLUDED.as_of,
			    received_at = EXCLUDED.received_at,
			    source = EXCLUDED.source
			WHERE EXCLUDED.as_of >= market_index_snapshot.as_of
			""")
			.param("indexCode", index.indexCode())
			.param("indexName", index.indexName())
			.param("currentValue", index.currentValue())
			.param("changeAmount", index.changeAmount())
			.param("changeRate", index.changeRate())
			.param("dataStatus", index.dataStatus().name())
			.param("asOf", atUtc(index.asOf()))
			.param("source", index.source());
		statement = nullable(statement, "volume", index.volume(), Types.BIGINT);
		statement.update();
	}

	@Override
	public List<ForeignLimitCollectionTarget> findForeignLimitTargets() {
		return jdbcClient.sql("""
			SELECT policy.stock_code, s.isin_code
			FROM foreign_limit_policy policy
			JOIN security s ON s.stock_code = policy.stock_code
			ORDER BY policy.stock_code
			""")
			.query((resultSet, rowNumber) -> new ForeignLimitCollectionTarget(
				resultSet.getString("stock_code"),
				resultSet.getString("isin_code")
			))
			.list();
	}

	@Override
	@Transactional
	public void saveForeignOwnership(String stockCode, ForeignOwnershipSnapshot snapshot) {
		JdbcClient.StatementSpec statement = jdbcClient.sql("""
			INSERT INTO foreign_ownership_snapshot (
			    security_id, base_date, foreign_owned_quantity, total_listed_quantity,
			    foreign_limit_quantity, available_quantity, ownership_rate,
			    limit_exhaustion_rate, collected_at, source
			)
			SELECT s.id, :baseDate, :foreignOwnedQuantity, :totalListedQuantity,
			       :foreignLimitQuantity, :availableQuantity, :ownershipRate,
			       :limitExhaustionRate, :collectedAt, :source
			FROM security s
			JOIN foreign_limit_policy policy ON policy.stock_code = s.stock_code
			WHERE s.stock_code = :stockCode
			ON CONFLICT (security_id, base_date) DO UPDATE
			SET foreign_owned_quantity = EXCLUDED.foreign_owned_quantity,
			    total_listed_quantity = EXCLUDED.total_listed_quantity,
			    foreign_limit_quantity = EXCLUDED.foreign_limit_quantity,
			    available_quantity = EXCLUDED.available_quantity,
			    ownership_rate = EXCLUDED.ownership_rate,
			    limit_exhaustion_rate = EXCLUDED.limit_exhaustion_rate,
			    collected_at = EXCLUDED.collected_at,
			    source = EXCLUDED.source
			WHERE EXCLUDED.collected_at >= foreign_ownership_snapshot.collected_at
			""")
			.param("stockCode", stockCode)
			.param("baseDate", snapshot.baseDate())
			.param("foreignOwnedQuantity", snapshot.foreignOwnedQuantity())
			.param("collectedAt", atUtc(snapshot.collectedAt()))
			.param("source", snapshot.source());
		statement = nullable(
			statement,
			"totalListedQuantity",
			snapshot.totalListedQuantity(),
			Types.BIGINT
		);
		statement = nullable(
			statement,
			"foreignLimitQuantity",
			snapshot.foreignLimitQuantity(),
			Types.BIGINT
		);
		statement = nullable(
			statement,
			"availableQuantity",
			snapshot.availableQuantity(),
			Types.BIGINT
		);
		statement = nullable(statement, "ownershipRate", snapshot.ownershipRate(), Types.NUMERIC);
		statement = nullable(
			statement,
			"limitExhaustionRate",
			snapshot.limitExhaustionRate(),
			Types.NUMERIC
		);
		statement.update();
	}

	@Override
	@Transactional
	public void saveDailyPrices(String stockCode, List<MarketDailyPrice> prices) {
		for (MarketDailyPrice price : prices) {
			jdbcClient.sql("""
				INSERT INTO market_daily_price (
				    security_id, trading_date, open_price_krw, high_price_krw,
				    low_price_krw, close_price_krw, volume, source, collected_at
				)
				SELECT security.id, :tradingDate, :openPrice, :highPrice,
				       :lowPrice, :closePrice, :volume, :source, CURRENT_TIMESTAMP
				FROM security
				JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
				WHERE security.stock_code = :stockCode
				ON CONFLICT (security_id, trading_date) DO UPDATE
				SET open_price_krw = EXCLUDED.open_price_krw,
				    high_price_krw = EXCLUDED.high_price_krw,
				    low_price_krw = EXCLUDED.low_price_krw,
				    close_price_krw = EXCLUDED.close_price_krw,
				    volume = EXCLUDED.volume,
				    source = EXCLUDED.source,
				    collected_at = EXCLUDED.collected_at
				""")
				.param("stockCode", stockCode)
				.param("tradingDate", price.tradingDate())
				.param("openPrice", price.openPriceKrw())
				.param("highPrice", price.highPriceKrw())
				.param("lowPrice", price.lowPriceKrw())
				.param("closePrice", price.closePriceKrw())
				.param("volume", price.volume())
				.param("source", price.source())
				.update();
		}
	}

	@Override
	@Transactional
	public void saveExchangeRate(ExchangeRateSnapshot snapshot) {
		jdbcClient.sql("""
			INSERT INTO exchange_rate_snapshot (
			    currency, krw_per_unit, data_status, as_of, source
			)
			VALUES (:currency, :rate, :status, :asOf, :source)
			ON CONFLICT (currency) DO UPDATE
			SET krw_per_unit = EXCLUDED.krw_per_unit,
			    data_status = EXCLUDED.data_status,
			    as_of = EXCLUDED.as_of,
			    source = EXCLUDED.source
			WHERE EXCLUDED.as_of >= exchange_rate_snapshot.as_of
			""")
			.param("currency", snapshot.currency())
			.param("rate", snapshot.krwPerUnit())
			.param("status", snapshot.dataStatus().name())
			.param("asOf", atUtc(snapshot.asOf()))
			.param("source", snapshot.source())
			.update();
	}

	@Override
	@Transactional
	public void saveForeignNetFlows(List<MarketForeignNetFlow> flows) {
		for (MarketForeignNetFlow flow : flows) {
			jdbcClient.sql("""
				INSERT INTO market_foreign_net_flow (
				    market_code, trading_date, net_purchase_amount_krw,
				    collected_at, source
				)
				VALUES (:marketCode, :tradingDate, :amount, :collectedAt, :source)
				ON CONFLICT (market_code, trading_date) DO UPDATE
				SET net_purchase_amount_krw = EXCLUDED.net_purchase_amount_krw,
				    collected_at = EXCLUDED.collected_at,
				    source = EXCLUDED.source
				WHERE EXCLUDED.collected_at >= market_foreign_net_flow.collected_at
				""")
				.param("marketCode", flow.marketCode())
				.param("tradingDate", flow.tradingDate())
				.param("amount", flow.netPurchaseAmountKrw())
				.param("collectedAt", atUtc(flow.collectedAt()))
				.param("source", flow.source())
				.update();
		}
	}

	private JdbcClient.StatementSpec nullable(
		JdbcClient.StatementSpec statement,
		String name,
		Object value,
		int sqlType
	) {
		return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
	}

	private OffsetDateTime atUtc(java.time.Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
