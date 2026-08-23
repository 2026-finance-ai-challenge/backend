package com.kmarket.navigator.backend.identity.infrastructure;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "kmarket.identity")
public class IdentityProperties {

	@NotNull
	private Duration accessTokenTtl = Duration.ofMinutes(15);

	@NotNull
	private Duration refreshTokenTtl = Duration.ofDays(14);

	@NotBlank
	@Size(min = 32, max = 256)
	private String contextPepper;

	@NotBlank
	private String jwtIssuer = "k-market-navigator";

	@NotBlank
	private String jwtAudience = "k-market-navigator-api";

	@NotBlank
	private String jwtSecretBase64;

	@NotBlank
	private String redisKeyPrefix = "kmarket:identity";

	public Duration accessTokenTtl() {
		return accessTokenTtl;
	}

	public void setAccessTokenTtl(Duration accessTokenTtl) {
		this.accessTokenTtl = accessTokenTtl;
	}

	public Duration refreshTokenTtl() {
		return refreshTokenTtl;
	}

	public void setRefreshTokenTtl(Duration refreshTokenTtl) {
		this.refreshTokenTtl = refreshTokenTtl;
	}

	public String contextPepper() {
		return contextPepper;
	}

	public void setContextPepper(String contextPepper) {
		this.contextPepper = contextPepper;
	}

	public String jwtIssuer() {
		return jwtIssuer;
	}

	public void setJwtIssuer(String jwtIssuer) {
		this.jwtIssuer = jwtIssuer;
	}

	public String jwtAudience() {
		return jwtAudience;
	}

	public void setJwtAudience(String jwtAudience) {
		this.jwtAudience = jwtAudience;
	}

	public String jwtSecretBase64() {
		return jwtSecretBase64;
	}

	public void setJwtSecretBase64(String jwtSecretBase64) {
		this.jwtSecretBase64 = jwtSecretBase64;
	}

	public String redisKeyPrefix() {
		return redisKeyPrefix;
	}

	public void setRedisKeyPrefix(String redisKeyPrefix) {
		this.redisKeyPrefix = redisKeyPrefix;
	}
}
