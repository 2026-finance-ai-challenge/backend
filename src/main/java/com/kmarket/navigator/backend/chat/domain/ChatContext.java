package com.kmarket.navigator.backend.chat.domain;

public record ChatContext(
	ChatContextType type,
	String referenceId,
	String version,
	String title
) {
}
