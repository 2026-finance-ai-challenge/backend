package com.kmarket.navigator.backend.identity.infrastructure;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

@Component
public class JwtTokenService {

	private static final int MINIMUM_HS512_SECRET_BYTES = 64;
	private final IdentityProperties properties;
	private final JwtEncoder encoder;
	private final JwtDecoder decoder;

	public JwtTokenService(IdentityProperties properties) {
		this.properties = properties;
		byte[] secret = decodeSecret(properties.jwtSecretBase64());
		SecretKey secretKey = new SecretKeySpec(secret, "HmacSHA512");
		this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey));
		NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey)
			.macAlgorithm(MacAlgorithm.HS512)
			.build();
		OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience()
			.contains(properties.jwtAudience())
			? OAuth2TokenValidatorResult.success()
			: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience.", null));
		jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
			JwtValidators.createDefaultWithIssuer(properties.jwtIssuer()),
			audienceValidator
		));
		this.decoder = jwtDecoder;
	}

	public IssuedJwt issue(UUID userId, UUID sessionId, Instant issuedAt, Instant expiresAt) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(properties.jwtIssuer())
			.audience(List.of(properties.jwtAudience()))
			.issuedAt(issuedAt)
			.expiresAt(expiresAt)
			.subject(userId.toString())
			.id(UUID.randomUUID().toString())
			.claim("sid", sessionId.toString())
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS512).type("JWT").build();
		return new IssuedJwt(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
	}

	public Optional<VerifiedJwt> verify(String token) {
		try {
			Jwt jwt = decoder.decode(token);
			return Optional.of(new VerifiedJwt(
				UUID.fromString(jwt.getSubject()),
				UUID.fromString(jwt.getClaimAsString("sid")),
				jwt.getExpiresAt()
			));
		}
		catch (JwtException | IllegalArgumentException | NullPointerException exception) {
			return Optional.empty();
		}
	}

	private byte[] decodeSecret(String encoded) {
		try {
			byte[] secret = Base64.getDecoder().decode(encoded);
			if (secret.length < MINIMUM_HS512_SECRET_BYTES) {
				throw new IllegalStateException("JWT 서명 키는 64바이트 이상이어야 합니다.");
			}
			return secret;
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalStateException("JWT 서명 키는 Base64 형식이어야 합니다.", exception);
		}
	}

	public record IssuedJwt(String token, Instant expiresAt) {
	}

	public record VerifiedJwt(UUID userId, UUID sessionId, Instant expiresAt) {
	}
}
