package com.kmarket.navigator.backend.chat.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.identity.infrastructure.IdentityProperties;

@Component
public class AgentSafetyIdentifier {

	private final byte[] key;

	public AgentSafetyIdentifier(IdentityProperties properties) {
		this.key = properties.contextPepper().getBytes(StandardCharsets.UTF_8);
	}

	public String from(UUID userId) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(
				userId.toString().getBytes(StandardCharsets.UTF_8)
			));
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
		}
	}
}
