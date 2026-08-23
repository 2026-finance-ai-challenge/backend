package com.kmarket.navigator.backend.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.identity.application.ClientContext;

@Component
public class ClientContextResolver {

	private static final String REQUEST_ID_ATTRIBUTE = "kmarket.requestId";
	private final IdentityProperties properties;

	public ClientContextResolver(IdentityProperties properties) {
		this.properties = properties;
	}

	public ClientContext resolve(HttpServletRequest request) {
		String userAgent = request.getHeader("User-Agent");
		Object requestId = request.getAttribute(REQUEST_ID_ATTRIBUTE);
		return new ClientContext(
			hash(request.getRemoteAddr()),
			hash(userAgent == null ? "" : userAgent),
			requestId == null ? null : requestId.toString()
		);
	}

	public String loginGuardKey(String loginId, ClientContext context) {
		return hash(loginId + ":" + context.ipHash());
	}

	private String hash(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(properties.contextPepper().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
		}
	}
}
