package com.kmarket.navigator.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@Profile("!backfill")
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(cache -> cache.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/disclosures").permitAll()
				.requestMatchers(
					HttpMethod.GET,
					"/api/v1/disclosures/{receiptNumber:[0-9]{14}}"
				).permitAll()
				.requestMatchers(
					HttpMethod.POST,
					"/api/v1/disclosures/{receiptNumber:[0-9]{14}}/questions"
				).permitAll()
				.requestMatchers(
					HttpMethod.POST,
					"/api/v1/disclosures/{receiptNumber:[0-9]{14}}/index"
				).permitAll()
				.anyRequest().denyAll());

		return http.build();
	}
}
