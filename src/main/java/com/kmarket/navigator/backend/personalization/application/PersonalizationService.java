package com.kmarket.navigator.backend.personalization.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;
import com.kmarket.navigator.backend.personalization.application.port.PersonalizationRepository;
import com.kmarket.navigator.backend.personalization.domain.NotificationCursor;
import com.kmarket.navigator.backend.personalization.domain.RecentlyViewedItem;
import com.kmarket.navigator.backend.personalization.domain.RecentItemType;
import com.kmarket.navigator.backend.personalization.domain.SupportedStock;
import com.kmarket.navigator.backend.personalization.domain.WatchlistItem;

@Service
public class PersonalizationService {

	private static final int RECENTLY_VIEWED_RETENTION = 100;
	private final PersonalizationRepository repository;

	public PersonalizationService(PersonalizationRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public List<WatchlistItem> watchlist(AuthenticatedUser user) {
		return repository.findWatchlist(user.id());
	}

	@Transactional
	public WatchlistItem addWatchlist(AuthenticatedUser user, String stockCode) {
		SupportedStock stock = supportedStock(stockCode);
		return repository.addWatchlist(user.id(), stock, Instant.now());
	}

	@Transactional
	public void removeWatchlist(AuthenticatedUser user, String stockCode) {
		SupportedStock stock = supportedStock(stockCode);
		repository.removeWatchlist(user.id(), stock.securityId());
	}

	@Transactional
	public RecentlyViewedItem recordRecentlyViewed(
		AuthenticatedUser user,
		RecentItemType itemType,
		String referenceId,
		String stockCode
	) {
		String normalizedStockCode = stockCode == null
			? null
			: normalizeStockCode(stockCode);
		if (normalizedStockCode != null) {
			supportedStock(normalizedStockCode);
		}
		validateReference(itemType, referenceId, normalizedStockCode);
		RecentlyViewedItem item = new RecentlyViewedItem(
			itemType,
			referenceId,
			normalizedStockCode,
			Instant.now()
		);
		repository.saveRecentlyViewed(user.id(), item, RECENTLY_VIEWED_RETENTION);
		return item;
	}

	@Transactional(readOnly = true)
	public List<RecentlyViewedItem> recentlyViewed(AuthenticatedUser user, int limit) {
		return repository.findRecentlyViewed(user.id(), limit);
	}

	@Transactional(readOnly = true)
	public NotificationPage notifications(AuthenticatedUser user, String cursor, int limit) {
		NotificationCursor decoded = cursor == null ? null : NotificationCursor.decode(cursor);
		var fetched = repository.findNotifications(user.id(), decoded, limit + 1);
		boolean hasNext = fetched.size() > limit;
		var items = hasNext ? fetched.subList(0, limit) : fetched;
		String nextCursor = hasNext
			? new NotificationCursor(
				items.getLast().createdAt(),
				items.getLast().id()
			).encode()
			: null;
		return new NotificationPage(
			List.copyOf(items),
			nextCursor,
			repository.countUnreadNotifications(user.id())
		);
	}

	@Transactional
	public void markNotificationRead(AuthenticatedUser user, UUID notificationId) {
		if (!repository.markNotificationRead(user.id(), notificationId, Instant.now())) {
			throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
		}
	}

	@Transactional
	public void markAllNotificationsRead(AuthenticatedUser user) {
		repository.markAllNotificationsRead(user.id(), Instant.now());
	}

	private SupportedStock supportedStock(String stockCode) {
		return repository.findSupportedStock(normalizeStockCode(stockCode))
			.orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_STOCK));
	}

	private void validateReference(RecentItemType type, String referenceId, String stockCode) {
		if (type == RecentItemType.STOCK
			&& (stockCode == null || !referenceId.equalsIgnoreCase(stockCode))) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		if (type == RecentItemType.FILING && !repository.supportedFilingExists(referenceId)) {
			throw new BusinessException(ErrorCode.DISCLOSURE_NOT_FOUND);
		}
	}

	private String normalizeStockCode(String stockCode) {
		return stockCode.trim().toUpperCase(Locale.ROOT);
	}
}
