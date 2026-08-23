package com.kmarket.navigator.backend.identity.domain;

import java.io.Serializable;
import java.util.UUID;

public record AuthenticatedUser(UUID id, String loginId) implements Serializable {
	private static final long serialVersionUID = 1L;
}
