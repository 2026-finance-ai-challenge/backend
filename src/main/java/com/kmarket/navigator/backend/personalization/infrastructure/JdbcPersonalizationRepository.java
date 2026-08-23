package com.kmarket.navigator.backend.personalization.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.kmarket.navigator.backend.personalization.application.port.PersonalizationRepository;
import com.kmarket.navigator.backend.personalization.domain.NotificationCursor;
import com.kmarket.navigator.backend.personalization.domain.RecentlyViewedItem;
import com.kmarket.navigator.backend.personalization.domain.RecentItemType;
import com.kmarket.navigator.backend.personalization.domain.SupportedStock;
import com.kmarket.navigator.backend.personalization.domain.UserNotification;
import com.kmarket.navigator.backend.personalization.domain.WatchlistItem;

@Repository
class JdbcPersonalizationRepository implements PersonalizationRepository {

	private static final String STOCK_COLUMNS = """
		s.id AS security_id, s.stock_code, i.name_ko, COALESCE(i.name_en, '') AS name_en, s.market
		""";
	private final JdbcClient jdbcClient;

	JdbcPersonalizationRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public Optional<SupportedStock> findSupportedStock(String stockCode) {
		return jdbcClient.sql("SELECT " + STOCK_COLUMNS + """
			FROM security s
			JOIN issuer i ON i.id = s.issuer_id
			JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			WHERE s.stock_code = :stockCode
			""")
			.param("stockCode", stockCode)
			.query(this::mapStock)
			.optional();
	}

	@Override
	public List<WatchlistItem> findWatchlist(UUID userId) {
		return jdbcClient.sql("SELECT " + STOCK_COLUMNS + """
			     , watchlist.added_at
			FROM watchlist_item watchlist
			JOIN security s ON s.id = watchlist.security_id
			JOIN issuer i ON i.id = s.issuer_id
			WHERE watchlist.user_id = :userId
			ORDER BY watchlist.added_at DESC, s.stock_code
			""")
			.param("userId", userId)
			.query((resultSet, rowNumber) -> new WatchlistItem(
				mapStock(resultSet, rowNumber),
				instant(resultSet, "added_at")
			))
			.list();
	}

	@Override
	public WatchlistItem addWatchlist(UUID userId, SupportedStock stock, Instant addedAt) {
		jdbcClient.sql("""
			INSERT INTO watchlist_item (user_id, security_id, added_at)
			VALUES (:userId, :securityId, :addedAt)
			ON CONFLICT (user_id, security_id) DO NOTHING
			""")
			.param("userId", userId)
			.param("securityId", stock.securityId())
			.param("addedAt", atUtc(addedAt))
			.update();
		Instant persistedAddedAt = jdbcClient.sql("""
			SELECT added_at
			FROM watchlist_item
			WHERE user_id = :userId AND security_id = :securityId
			""")
			.param("userId", userId)
			.param("securityId", stock.securityId())
			.query(OffsetDateTime.class)
			.single()
			.toInstant();
		return new WatchlistItem(stock, persistedAddedAt);
	}

	@Override
	public void removeWatchlist(UUID userId, UUID securityId) {
		jdbcClient.sql("""
			DELETE FROM watchlist_item
			WHERE user_id = :userId AND security_id = :securityId
			""")
			.param("userId", userId)
			.param("securityId", securityId)
			.update();
	}

	@Override
	public boolean supportedFilingExists(String receiptNumber) {
		return jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM disclosure d
			    JOIN security s ON s.id = d.security_id
			    JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			    WHERE d.receipt_number = :receiptNumber
			)
			""")
			.param("receiptNumber", receiptNumber)
			.query(Boolean.class)
			.single();
	}

	@Override
	public void saveRecentlyViewed(UUID userId, RecentlyViewedItem item, int retainedItems) {
		JdbcClient.StatementSpec statement = jdbcClient.sql("""
			INSERT INTO recently_viewed_item (
			    user_id, item_type, reference_id, stock_code, viewed_at
			)
			VALUES (:userId, :itemType, :referenceId, :stockCode, :viewedAt)
			ON CONFLICT (user_id, item_type, reference_id) DO UPDATE
			SET stock_code = EXCLUDED.stock_code, viewed_at = EXCLUDED.viewed_at
			""")
			.param("userId", userId)
			.param("itemType", item.itemType().name())
			.param("referenceId", item.referenceId())
			.param("viewedAt", atUtc(item.viewedAt()));
		statement = item.stockCode() == null
			? statement.param("stockCode", null, Types.VARCHAR)
			: statement.param("stockCode", item.stockCode());
		statement.update();

		jdbcClient.sql("""
			DELETE FROM recently_viewed_item
			WHERE ctid IN (
			    SELECT ctid
			    FROM recently_viewed_item
			    WHERE user_id = :userId
			    ORDER BY viewed_at DESC, item_type, reference_id
			    OFFSET :retainedItems
			)
			""")
			.param("userId", userId)
			.param("retainedItems", retainedItems)
			.update();
	}

	@Override
	public List<RecentlyViewedItem> findRecentlyViewed(UUID userId, int limit) {
		return jdbcClient.sql("""
			SELECT item_type, reference_id, stock_code, viewed_at
			FROM recently_viewed_item
			WHERE user_id = :userId
			ORDER BY viewed_at DESC, item_type, reference_id
			LIMIT :limit
			""")
			.param("userId", userId)
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new RecentlyViewedItem(
				RecentItemType.valueOf(resultSet.getString("item_type")),
				resultSet.getString("reference_id"),
				resultSet.getString("stock_code"),
				instant(resultSet, "viewed_at")
			))
			.list();
	}

	@Override
	public List<UserNotification> findNotifications(UUID userId, NotificationCursor cursor, int limit) {
		if (cursor == null) {
			return jdbcClient.sql("""
				SELECT id, notification_type, title, body, reference_type, reference_id,
				       created_at, read_at
				FROM user_notification
				WHERE user_id = :userId
				ORDER BY created_at DESC, id DESC
				LIMIT :limit
				""")
				.param("userId", userId)
				.param("limit", limit)
				.query(this::mapNotification)
				.list();
		}
		return jdbcClient.sql("""
			SELECT id, notification_type, title, body, reference_type, reference_id,
			       created_at, read_at
			FROM user_notification
			WHERE user_id = :userId
			  AND (created_at, id) < (:createdAt, :id)
			ORDER BY created_at DESC, id DESC
			LIMIT :limit
			""")
			.param("userId", userId)
			.param("createdAt", atUtc(cursor.createdAt()))
			.param("id", cursor.id())
			.param("limit", limit)
			.query(this::mapNotification)
			.list();
	}

	@Override
	public long countUnreadNotifications(UUID userId) {
		return jdbcClient.sql("""
			SELECT COUNT(*)
			FROM user_notification
			WHERE user_id = :userId AND read_at IS NULL
			""")
			.param("userId", userId)
			.query(Long.class)
			.single();
	}

	@Override
	public boolean markNotificationRead(UUID userId, UUID notificationId, Instant readAt) {
		return jdbcClient.sql("""
			UPDATE user_notification
			SET read_at = COALESCE(read_at, :readAt)
			WHERE id = :notificationId AND user_id = :userId
			RETURNING id
			""")
			.param("readAt", atUtc(readAt))
			.param("notificationId", notificationId)
			.param("userId", userId)
			.query(UUID.class)
			.optional()
			.isPresent();
	}

	@Override
	public void markAllNotificationsRead(UUID userId, Instant readAt) {
		jdbcClient.sql("""
			UPDATE user_notification
			SET read_at = :readAt
			WHERE user_id = :userId AND read_at IS NULL
			""")
			.param("readAt", atUtc(readAt))
			.param("userId", userId)
			.update();
	}

	private SupportedStock mapStock(ResultSet resultSet, int rowNumber) throws SQLException {
		return new SupportedStock(
			resultSet.getObject("security_id", UUID.class),
			resultSet.getString("stock_code"),
			resultSet.getString("name_ko"),
			resultSet.getString("name_en"),
			resultSet.getString("market")
		);
	}

	private UserNotification mapNotification(ResultSet resultSet, int rowNumber) throws SQLException {
		OffsetDateTime readAt = resultSet.getObject("read_at", OffsetDateTime.class);
		return new UserNotification(
			resultSet.getObject("id", UUID.class),
			resultSet.getString("notification_type"),
			resultSet.getString("title"),
			resultSet.getString("body"),
			resultSet.getString("reference_type"),
			resultSet.getString("reference_id"),
			instant(resultSet, "created_at"),
			readAt == null ? null : readAt.toInstant()
		);
	}

	private Instant instant(ResultSet resultSet, String column) throws SQLException {
		return resultSet.getObject(column, OffsetDateTime.class).toInstant();
	}

	private OffsetDateTime atUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
