package com.kmarket.navigator.backend.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
public class RefreshTokenService {

	private static final int TOKEN_BYTES = 48;
	private final SecureRandom secureRandom = new SecureRandom();

	public String issue() {
		byte[] entropy = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(entropy);
		return "kmr_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
	}

	public String hash(String token) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))
			);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}
}
