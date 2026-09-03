package com.kmarket.navigator.backend.stock.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.kmarket.navigator.backend.stock.application.port.MarketRepository;
import com.kmarket.navigator.backend.stock.domain.ExchangeRateSnapshot;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPolicy;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPrediction;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketDailyPrice;
import com.kmarket.navigator.backend.stock.domain.MarketDataStatus;
import com.kmarket.navigator.backend.stock.domain.MarketIndexSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketForeignNetFlowSummary;
import com.kmarket.navigator.backend.stock.domain.MarketQuoteSnapshot;
import com.kmarket.navigator.backend.stock.domain.PriceLimitState;
import com.kmarket.navigator.backend.stock.domain.StockIdentity;
import com.kmarket.navigator.backend.stock.domain.StockMarketView;

@Repository
class JdbcMarketRepository implements MarketRepository {

	private static final String VIEW_COLUMNS = """
		s.id AS security_id, s.stock_code, i.name_ko, COALESCE(i.name_en, '') AS name_en,
		s.market, COALESCE(s.sector, '') AS sector, (watchlist.user_id IS NOT NULL) AS watchlisted,
		quote.current_price_krw, quote.change_amount_krw, quote.change_rate,
		quote.open_price_krw, quote.high_price_krw, quote.low_price_krw, quote.volume,
		quote.market_session, quote.vi_active, quote.single_price_trading,
		quote.price_limit_state, quote.trading_halted, quote.trading_halt_reason,
		quote.status_available, quote.data_status AS quote_data_status, quote.as_of AS quote_as_of,
		quote.source AS quote_source,
		ownership.foreign_owned_quantity, ownership.total_listed_quantity,
		ownership.foreign_limit_quantity, ownership.available_quantity,
		ownership.ownership_rate, ownership.limit_exhaustion_rate,
		ownership.base_date AS ownership_base_date,
		ownership.collected_at AS ownership_collected_at, ownership.source AS ownership_source
		""";
	private final JdbcClient jdbcClient;

	JdbcMarketRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public List<StockIdentity> searchStocks(String query, UUID userId, int limit) {
		String normalized = query.trim().toLowerCase(Locale.ROOT);
		JdbcClient.StatementSpec statement = jdbcClient.sql("""
			SELECT s.id AS security_id, s.stock_code, i.name_ko,
			       COALESCE(i.name_en, '') AS name_en, s.market,
			       COALESCE(s.sector, '') AS sector,
			       (watchlist.user_id IS NOT NULL) AS watchlisted,
			       CASE
			           WHEN LOWER(s.stock_code) = :query THEN 100
			           WHEN LOWER(i.name_ko) = :query OR LOWER(COALESCE(i.name_en, '')) = :query THEN 98
			           WHEN EXISTS (
			               SELECT 1 FROM stock_alias alias
			               WHERE alias.security_id = s.id AND alias.normalized_alias = :query
			           ) THEN 96
			           WHEN LOWER(s.stock_code) LIKE :prefix THEN 92
			           WHEN LOWER(i.name_ko) LIKE :prefix
			             OR LOWER(COALESCE(i.name_en, '')) LIKE :prefix THEN 88
			           ELSE 70 + 20 * GREATEST(
			               SIMILARITY(LOWER(i.name_ko), :query),
			               SIMILARITY(LOWER(COALESCE(i.name_en, '')), :query),
			               COALESCE((
			                   SELECT MAX(SIMILARITY(alias.normalized_alias, :query))
			                   FROM stock_alias alias WHERE alias.security_id = s.id
			               ), 0)
			           )
			       END AS relevance
			FROM security s
			JOIN issuer i ON i.id = s.issuer_id
			JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			LEFT JOIN watchlist_item watchlist
			  ON watchlist.security_id = s.id AND watchlist.user_id = :userId
			WHERE LOWER(s.stock_code) LIKE :contains
			   OR LOWER(i.name_ko) LIKE :contains
			   OR LOWER(COALESCE(i.name_en, '')) LIKE :contains
			   OR SIMILARITY(LOWER(i.name_ko), :query) >= 0.2
			   OR SIMILARITY(LOWER(COALESCE(i.name_en, '')), :query) >= 0.2
			   OR EXISTS (
			       SELECT 1 FROM stock_alias alias
			       WHERE alias.security_id = s.id
			         AND (alias.normalized_alias LIKE :contains
			              OR SIMILARITY(alias.normalized_alias, :query) >= 0.2)
			   )
			ORDER BY relevance DESC, s.stock_code
			LIMIT :limit
			""")
			.param("query", normalized)
			.param("prefix", normalized + "%")
			.param("contains", "%" + normalized + "%")
			.param("limit", limit);
		return bindUser(statement, userId)
			.query(this::mapStockIdentity)
			.list();
	}

	@Override
	public List<StockMarketView> findStocks(UUID userId) {
		JdbcClient.StatementSpec statement = jdbcClient.sql("SELECT " + VIEW_COLUMNS + """
			FROM security s
			JOIN issuer i ON i.id = s.issuer_id
			JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			LEFT JOIN watchlist_item watchlist
			  ON watchlist.security_id = s.id AND watchlist.user_id = :userId
			LEFT JOIN market_quote_snapshot quote ON quote.security_id = s.id
			LEFT JOIN LATERAL (
			    SELECT snapshot.*
			    FROM foreign_ownership_snapshot snapshot
			    WHERE snapshot.security_id = s.id
			    ORDER BY snapshot.base_date DESC
			    LIMIT 1
			) ownership ON TRUE
			ORDER BY s.stock_code
			""");
		return bindUser(statement, userId).query(this::mapStockView).list();
	}

	@Override
	public Optional<StockMarketView> findStock(String stockCode, UUID userId) {
		JdbcClient.StatementSpec statement = jdbcClient.sql("SELECT " + VIEW_COLUMNS + """
			FROM security s
			JOIN issuer i ON i.id = s.issuer_id
			JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			LEFT JOIN watchlist_item watchlist
			  ON watchlist.security_id = s.id AND watchlist.user_id = :userId
			LEFT JOIN market_quote_snapshot quote ON quote.security_id = s.id
			LEFT JOIN LATERAL (
			    SELECT snapshot.*
			    FROM foreign_ownership_snapshot snapshot
			    WHERE snapshot.security_id = s.id
			    ORDER BY snapshot.base_date DESC
			    LIMIT 1
			) ownership ON TRUE
			WHERE s.stock_code = :stockCode
			""").param("stockCode", stockCode);
		return bindUser(statement, userId).query(this::mapStockView).optional();
	}

	@Override
	public List<ForeignLimitPolicy> findForeignLimitPolicies() {
		return jdbcClient.sql("""
			SELECT stock_code, warning_threshold, effective_from
			FROM foreign_limit_policy
			ORDER BY stock_code
			""")
			.query((resultSet, rowNumber) -> new ForeignLimitPolicy(
				resultSet.getString("stock_code"),
				resultSet.getBigDecimal("warning_threshold"),
				resultSet.getObject("effective_from", LocalDate.class)
			))
			.list();
	}

	@Override
	public List<ForeignOwnershipSnapshot> findForeignOwnershipHistory(UUID securityId, int limit) {
		return jdbcClient.sql("""
			SELECT foreign_owned_quantity, total_listed_quantity, foreign_limit_quantity,
			       available_quantity, ownership_rate, limit_exhaustion_rate,
			       base_date, collected_at, source
			FROM foreign_ownership_snapshot
			WHERE security_id = :securityId
			ORDER BY base_date DESC
			LIMIT :limit
			""")
			.param("securityId", securityId)
			.param("limit", limit)
			.query(this::mapForeignOwnership)
			.list();
	}

	@Override
	public Optional<ForeignLimitPrediction> findLatestForeignLimitPrediction(UUID securityId) {
		return findForeignLimitPredictionBefore(securityId, LocalDate.of(9999, 12, 31));
	}

	@Override
	public Optional<ForeignLimitPrediction> findForeignLimitPredictionBefore(UUID securityId, LocalDate targetDate) {
		return jdbcClient.sql("""
			SELECT min_rate, base_rate, max_rate, observation_count,
			       observation_window_days, confidence, model_version,
			       base_date, calculated_at, source
			FROM foreign_limit_prediction_snapshot
			WHERE security_id = :securityId AND base_date < :targetDate
			ORDER BY base_date DESC
			LIMIT 1
			""")
			.param("securityId", securityId)
			.param("targetDate", targetDate)
			.query((resultSet, rowNumber) -> new ForeignLimitPrediction(
				resultSet.getBigDecimal("min_rate"),
				resultSet.getBigDecimal("base_rate"),
				resultSet.getBigDecimal("max_rate"),
				resultSet.getInt("observation_count"),
				resultSet.getInt("observation_window_days"),
				resultSet.getBigDecimal("confidence"),
				resultSet.getString("model_version"),
				resultSet.getObject("base_date", LocalDate.class),
				instant(resultSet, "calculated_at"),
				resultSet.getString("source")
			))
			.optional();
	}

	@Override
	public List<MarketIndexSnapshot> findMarketIndices() {
		return jdbcClient.sql("""
			SELECT index_code, index_name, current_value, change_amount, change_rate,
			       volume, data_status, as_of, source
			FROM market_index_snapshot
			ORDER BY CASE index_code WHEN '0001' THEN 1 WHEN '1001' THEN 2 ELSE 3 END
			""")
			.query((resultSet, rowNumber) -> new MarketIndexSnapshot(
				resultSet.getString("index_code"),
				resultSet.getString("index_name"),
				resultSet.getBigDecimal("current_value"),
				resultSet.getBigDecimal("change_amount"),
				resultSet.getBigDecimal("change_rate"),
				nullableLong(resultSet, "volume"),
				MarketDataStatus.valueOf(resultSet.getString("data_status")),
				instant(resultSet, "as_of"),
				resultSet.getString("source")
			))
			.list();
	}

	@Override
	public Optional<ExchangeRateSnapshot> findExchangeRate(String currency) {
		return jdbcClient.sql("""
			SELECT currency, krw_per_unit, data_status, as_of, source
			FROM exchange_rate_snapshot
			WHERE currency = :currency
			""")
			.param("currency", currency)
			.query((resultSet, rowNumber) -> new ExchangeRateSnapshot(
				resultSet.getString("currency"),
				resultSet.getBigDecimal("krw_per_unit"),
				MarketDataStatus.valueOf(resultSet.getString("data_status")),
				instant(resultSet, "as_of"),
				resultSet.getString("source")
			))
			.optional();
	}

	@Override
	public Optional<MarketForeignNetFlowSummary> findLatestForeignNetFlow() {
		List<ForeignFlowRow> rows = jdbcClient.sql("""
			SELECT trading_date, SUM(net_purchase_amount_krw) AS total_amount,
			       MAX(collected_at) AS collected_at,
			       string_agg(DISTINCT source, '+') AS source
			FROM market_foreign_net_flow
			WHERE trading_date <= :latestStartedDate
			  AND market_code IN ('KOSPI', 'KOSDAQ')
			GROUP BY trading_date
			HAVING COUNT(DISTINCT market_code) = 2
			ORDER BY trading_date DESC
			LIMIT 30
			""")
			.param("latestStartedDate", com.kmarket.navigator.backend.stock.domain.MarketQuoteWindow.latestStartedDate(java.time.Instant.now()))
			.query((resultSet, rowNumber) -> new ForeignFlowRow(
				resultSet.getObject("trading_date", LocalDate.class),
				resultSet.getBigDecimal("total_amount"),
				instant(resultSet, "collected_at"),
				resultSet.getString("source")
			))
			.list();
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		int sign = rows.getFirst().amount().signum();
		int consecutiveDays = 0;
		for (ForeignFlowRow row : rows) {
			if (sign == 0 || row.amount().signum() != sign) {
				break;
			}
			consecutiveDays++;
		}
		ForeignFlowRow latest = rows.getFirst();
		return Optional.of(new MarketForeignNetFlowSummary(
			latest.tradingDate(), latest.amount(), consecutiveDays,
			com.kmarket.navigator.backend.stock.domain.MarketDataStatus.CLOSED,
			latest.collectedAt(), latest.source()
		));
	}

	@Override
	public List<MarketDailyPrice> findDailyPrices(
		UUID securityId,
		LocalDate from,
		LocalDate to,
		int limit
	) {
		StringBuilder sql = new StringBuilder("""
			SELECT trading_date, open_price_krw, high_price_krw, low_price_krw,
			       close_price_krw, volume, source
			FROM market_daily_price
			WHERE security_id = :securityId
			""");
		if (from != null) {
			sql.append(" AND trading_date >= :fromDate");
		}
		if (to != null) {
			sql.append(" AND trading_date <= :toDate");
		}
		sql.append(" ORDER BY trading_date DESC LIMIT :limit");
		JdbcClient.StatementSpec statement = jdbcClient.sql(sql.toString())
			.param("securityId", securityId)
			.param("limit", limit);
		if (from != null) {
			statement = statement.param("fromDate", from);
		}
		if (to != null) {
			statement = statement.param("toDate", to);
		}
		return statement.query((resultSet, rowNumber) -> new MarketDailyPrice(
			resultSet.getObject("trading_date", LocalDate.class),
			resultSet.getBigDecimal("open_price_krw"),
			resultSet.getBigDecimal("high_price_krw"),
			resultSet.getBigDecimal("low_price_krw"),
			resultSet.getBigDecimal("close_price_krw"),
			resultSet.getLong("volume"),
			resultSet.getString("source")
		)).list().reversed();
	}

	private JdbcClient.StatementSpec bindUser(JdbcClient.StatementSpec statement, UUID userId) {
		return userId == null
			? statement.param("userId", null, Types.OTHER)
			: statement.param("userId", userId);
	}

	private StockMarketView mapStockView(ResultSet resultSet, int rowNumber) throws SQLException {
		return new StockMarketView(
			mapStockIdentity(resultSet, rowNumber),
			mapQuote(resultSet),
			mapViewForeignOwnership(resultSet)
		);
	}

	private StockIdentity mapStockIdentity(ResultSet resultSet, int rowNumber) throws SQLException {
		return new StockIdentity(
			resultSet.getObject("security_id", UUID.class),
			resultSet.getString("stock_code"),
			resultSet.getString("name_ko"),
			resultSet.getString("name_en"),
			resultSet.getString("market"),
			resultSet.getString("sector"),
			resultSet.getBoolean("watchlisted")
		);
	}

	private MarketQuoteSnapshot mapQuote(ResultSet resultSet) throws SQLException {
		if (resultSet.getBigDecimal("current_price_krw") == null) {
			return null;
		}
		String priceLimit = resultSet.getString("price_limit_state");
		return new MarketQuoteSnapshot(
			resultSet.getBigDecimal("current_price_krw"),
			resultSet.getBigDecimal("change_amount_krw"),
			resultSet.getBigDecimal("change_rate"),
			resultSet.getBigDecimal("open_price_krw"),
			resultSet.getBigDecimal("high_price_krw"),
			resultSet.getBigDecimal("low_price_krw"),
			resultSet.getLong("volume"),
			resultSet.getString("market_session"),
			nullableBoolean(resultSet, "vi_active"),
			nullableBoolean(resultSet, "single_price_trading"),
			priceLimit == null ? null : PriceLimitState.valueOf(priceLimit),
			nullableBoolean(resultSet, "trading_halted"),
			resultSet.getString("trading_halt_reason"),
			resultSet.getBoolean("status_available"),
			MarketDataStatus.valueOf(resultSet.getString("quote_data_status")),
			instant(resultSet, "quote_as_of"),
			resultSet.getString("quote_source")
		);
	}

	private ForeignOwnershipSnapshot mapForeignOwnership(ResultSet resultSet, int rowNumber)
		throws SQLException {
		return mapForeignOwnership(
			resultSet,
			"base_date",
			"collected_at",
			"source"
		);
	}

	private ForeignOwnershipSnapshot mapViewForeignOwnership(ResultSet resultSet) throws SQLException {
		return mapForeignOwnership(
			resultSet,
			"ownership_base_date",
			"ownership_collected_at",
			"ownership_source"
		);
	}

	private ForeignOwnershipSnapshot mapForeignOwnership(
		ResultSet resultSet,
		String baseDateColumn,
		String collectedAtColumn,
		String sourceColumn
	) throws SQLException {
		Long foreignOwned = nullableLong(resultSet, "foreign_owned_quantity");
		if (foreignOwned == null) {
			return null;
		}
		return new ForeignOwnershipSnapshot(
			foreignOwned,
			nullableLong(resultSet, "total_listed_quantity"),
			nullableLong(resultSet, "foreign_limit_quantity"),
			nullableLong(resultSet, "available_quantity"),
			resultSet.getBigDecimal("ownership_rate"),
			resultSet.getBigDecimal("limit_exhaustion_rate"),
			resultSet.getObject(baseDateColumn, LocalDate.class),
			instant(resultSet, collectedAtColumn),
			resultSet.getString(sourceColumn)
		);
	}

	private Boolean nullableBoolean(ResultSet resultSet, String column) throws SQLException {
		boolean value = resultSet.getBoolean(column);
		return resultSet.wasNull() ? null : value;
	}

	private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
		long value = resultSet.getLong(column);
		return resultSet.wasNull() ? null : value;
	}

	private java.time.Instant instant(ResultSet resultSet, String column) throws SQLException {
		return resultSet.getObject(column, OffsetDateTime.class).toInstant();
	}

	private record ForeignFlowRow(
		LocalDate tradingDate,
		java.math.BigDecimal amount,
		java.time.Instant collectedAt,
		String source
	) {
	}

}
