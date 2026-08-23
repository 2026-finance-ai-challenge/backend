package com.kmarket.navigator.backend.personalization.presentation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;
import com.kmarket.navigator.backend.personalization.application.NotificationPage;
import com.kmarket.navigator.backend.personalization.application.PersonalizationService;
import com.kmarket.navigator.backend.personalization.domain.RecentlyViewedItem;
import com.kmarket.navigator.backend.personalization.domain.RecentItemType;
import com.kmarket.navigator.backend.personalization.domain.UserNotification;
import com.kmarket.navigator.backend.personalization.domain.WatchlistItem;

@Validated
@RestController
@RequestMapping("/api/v1/me")
public class PersonalizationController {

	private static final String STOCK_CODE_PATTERN = "^[0-9A-Za-z]{6}$";
	private final PersonalizationService service;

	public PersonalizationController(PersonalizationService service) {
		this.service = service;
	}

	@GetMapping("/watchlist")
	public ResponseEntity<WatchlistResponse> watchlist(@AuthenticationPrincipal AuthenticatedUser user) {
		List<WatchlistResponse.Item> items = service.watchlist(user).stream()
			.map(WatchlistResponse.Item::from)
			.toList();
		return noStore(new WatchlistResponse(items.size(), items));
	}

	@PutMapping("/watchlist/{stockCode}")
	public ResponseEntity<WatchlistResponse.Item> addWatchlist(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable @Pattern(regexp = STOCK_CODE_PATTERN) String stockCode
	) {
		return noStore(WatchlistResponse.Item.from(service.addWatchlist(user, stockCode)));
	}

	@DeleteMapping("/watchlist/{stockCode}")
	public ResponseEntity<Void> removeWatchlist(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable @Pattern(regexp = STOCK_CODE_PATTERN) String stockCode
	) {
		service.removeWatchlist(user, stockCode);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@PostMapping("/recently-viewed")
	public ResponseEntity<RecentlyViewedResponse> recordRecentlyViewed(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody RecentlyViewedRequest body
	) {
		RecentlyViewedItem item = service.recordRecentlyViewed(
			user,
			body.itemType(),
			body.referenceId(),
			body.stockCode()
		);
		return noStore(RecentlyViewedResponse.from(item));
	}

	@GetMapping("/recently-viewed")
	public ResponseEntity<List<RecentlyViewedResponse>> recentlyViewed(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
	) {
		return noStore(service.recentlyViewed(user, limit).stream()
			.map(RecentlyViewedResponse::from)
			.toList());
	}

	@GetMapping("/notifications")
	public ResponseEntity<NotificationInboxResponse> notifications(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam(required = false) String cursor,
		@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
	) {
		NotificationPage page = service.notifications(user, cursor, limit);
		return noStore(new NotificationInboxResponse(
			page.items().stream().map(NotificationResponse::from).toList(),
			page.nextCursor(),
			page.unreadCount()
		));
	}

	@PutMapping("/notifications/read-all")
	public ResponseEntity<Void> markAllNotificationsRead(@AuthenticationPrincipal AuthenticatedUser user) {
		service.markAllNotificationsRead(user);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@PutMapping("/notifications/{notificationId}/read")
	public ResponseEntity<Void> markNotificationRead(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID notificationId
	) {
		service.markNotificationRead(user, notificationId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	public record RecentlyViewedRequest(
		@NotNull RecentItemType itemType,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String referenceId,
		@Pattern(regexp = STOCK_CODE_PATTERN) String stockCode
	) {
	}

	public record RecentlyViewedResponse(
		RecentItemType itemType,
		String referenceId,
		String stockCode,
		Instant viewedAt
	) {
		static RecentlyViewedResponse from(RecentlyViewedItem item) {
			return new RecentlyViewedResponse(
				item.itemType(),
				item.referenceId(),
				item.stockCode(),
				item.viewedAt()
			);
		}
	}

	public record WatchlistResponse(int count, List<Item> items) {

		public record Item(
			String stockCode,
			String nameKo,
			String nameEn,
			String market,
			Instant addedAt
		) {
			static Item from(WatchlistItem item) {
				return new Item(
					item.stock().stockCode(),
					item.stock().nameKo(),
					item.stock().nameEn(),
					item.stock().market(),
					item.addedAt()
				);
			}
		}
	}

	public record NotificationInboxResponse(
		List<NotificationResponse> items,
		String nextCursor,
		long unreadCount
	) {
	}

	public record NotificationResponse(
		UUID id,
		String notificationType,
		String title,
		String body,
		String referenceType,
		String referenceId,
		Instant createdAt,
		Instant readAt,
		boolean read
	) {
		static NotificationResponse from(UserNotification notification) {
			return new NotificationResponse(
				notification.id(),
				notification.notificationType(),
				notification.title(),
				notification.body(),
				notification.referenceType(),
				notification.referenceId(),
				notification.createdAt(),
				notification.readAt(),
				notification.readAt() != null
			);
		}
	}
}
