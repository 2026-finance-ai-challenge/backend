package com.kmarket.navigator.backend.personalization.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kmarket.navigator.backend.personalization.domain.NotificationCursor;
import com.kmarket.navigator.backend.personalization.domain.RecentlyViewedItem;
import com.kmarket.navigator.backend.personalization.domain.RecentItemType;
import com.kmarket.navigator.backend.personalization.domain.SupportedStock;
import com.kmarket.navigator.backend.personalization.domain.UserNotification;
import com.kmarket.navigator.backend.personalization.domain.WatchlistItem;

public interface PersonalizationRepository {
	Optional<SupportedStock> findSupportedStock(String stockCode);

	List<WatchlistItem> findWatchlist(UUID userId);

	WatchlistItem addWatchlist(UUID userId, SupportedStock stock, Instant addedAt);

	void removeWatchlist(UUID userId, UUID securityId);

	boolean supportedFilingExists(String receiptNumber);

	void saveRecentlyViewed(UUID userId, RecentlyViewedItem item, int retainedItems);

	List<RecentlyViewedItem> findRecentlyViewed(UUID userId, int limit);

	List<UserNotification> findNotifications(UUID userId, NotificationCursor cursor, int limit);

	long countUnreadNotifications(UUID userId);

	boolean markNotificationRead(UUID userId, UUID notificationId, Instant readAt);

	void markAllNotificationsRead(UUID userId, Instant readAt);
}
