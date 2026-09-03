package com.kmarket.navigator.backend.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.GeneralSecurityException;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
public class RefreshTokenService {

	private static final int TOKEN_BYTES = 48;
	private final SecureRandom secureRandom = new SecureRandom();
	private final byte[] replayKey;

	public RefreshTokenService(IdentityProperties properties) {
		this.replayKey = hmac(properties.contextPepper().getBytes(StandardCharsets.UTF_8), "kart-browser-refresh-v1");
	}

	public String browserSuccessor(String oldToken, UUID requestId) {
		// 동일 요청의 응답 유실은 복구하되 Redis에는 토큰 원문을 저장하지 않는다.
		return "kmr_" + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(replayKey, oldToken + ":" + requestId));
	}

	private byte[] hmac(byte[] key, String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA384");
			mac.init(new SecretKeySpec(key, "HmacSHA384"));
			return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("인증 요청 키를 생성할 수 없습니다.", exception);
		}
	}

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
