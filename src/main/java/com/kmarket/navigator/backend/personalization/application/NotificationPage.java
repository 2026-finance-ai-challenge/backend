package com.kmarket.navigator.backend.personalization.application;

import java.util.List;

import com.kmarket.navigator.backend.personalization.domain.UserNotification;

public record NotificationPage(
	List<UserNotification> items,
	String nextCursor,
	long unreadCount
) {
}
