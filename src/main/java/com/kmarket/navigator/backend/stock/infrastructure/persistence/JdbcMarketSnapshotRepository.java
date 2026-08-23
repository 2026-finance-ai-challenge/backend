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
