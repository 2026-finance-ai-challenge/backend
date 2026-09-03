package com.kmarket.navigator.backend.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record AuthSession(
	UUID id,
	UUID familyId,
	UUID userId,
	String refreshTokenHash,
	String issuedIpHash,
	String issuedUserAgentHash,
	Instant issuedAt,
	Instant accessExpiresAt,
	Instant refreshExpiresAt,
	String state,
	UUID replacedBySessionId
) {
	public boolean accessActiveAt(Instant now) {
		// Refresh 회전은 다른 탭의 미만료 Access JWT를 폐기하지 않는다.
		return ("ACTIVE".equals(state) || "ROTATED".equals(state)) && accessExpiresAt.isAfter(now);
	}

	public boolean refreshActiveAt(Instant now) {
		return "ACTIVE".equals(state) && refreshExpiresAt.isAfter(now);
	}
}
