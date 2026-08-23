package com.kmarket.navigator.backend.identity.infrastructure;

import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
class IdentityConfiguration {

	@Bean
	PasswordEncoder passwordEncoder() {
		Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
		return new DelegatingPasswordEncoder("argon2", Map.of("argon2", argon2));
	}
}
