package com.kmarket.navigator.backend.identity.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.kmarket.navigator.backend.identity.domain.UserAccount;

public interface IdentityRepository {
	boolean existsByLoginId(String loginId);

	void insert(UserAccount account, Instant termsAcceptedAt, Instant privacyAcceptedAt);

	Optional<UserAccount> findActiveByLoginId(String loginId);

	Optional<UserAccount> findActiveById(UUID userId);

	void updatePassword(UUID userId, String passwordHash, Instant updatedAt);

	void softDelete(UUID userId, Instant deletedAt);
}
