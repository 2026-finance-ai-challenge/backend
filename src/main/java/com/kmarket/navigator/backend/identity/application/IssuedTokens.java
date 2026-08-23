package com.kmarket.navigator.backend.identity.application;

import java.time.Instant;

public record IssuedTokens(
	String accessToken,
	Instant accessExpiresAt,
	String refreshToken,
	Instant refreshExpiresAt
) {
}
