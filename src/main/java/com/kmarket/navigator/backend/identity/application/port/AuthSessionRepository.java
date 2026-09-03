package com.kmarket.navigator.backend.identity.application.port;

import java.util.Optional;
import java.util.UUID;

import com.kmarket.navigator.backend.identity.domain.AuthSession;

public interface AuthSessionRepository {
	void insert(AuthSession session);

	Optional<AuthSession> findActiveById(UUID sessionId);

	Optional<AuthSession> findByRefreshTokenHash(String tokenHash);

	RotationResult rotate(AuthSession oldSession, AuthSession replacement);

	boolean revoke(String refreshTokenHash, UUID userId);

	void revokeAll(UUID userId);

	enum RotationResult {
		ROTATED,
		REPLAYED,
		MISSING,
		REUSED,
		EXPIRED
	}
}
