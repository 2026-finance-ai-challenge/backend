package com.kmarket.navigator.backend.global.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.kmarket.navigator.backend.identity.presentation.BearerTokenAuthenticationFilter;
import com.kmarket.navigator.backend.identity.presentation.ProblemSecurityHandler;

@Configuration(proxyBeanMethods = false)
@Profile("!backfill")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(
		HttpSecurity http,
		BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter,
		ProblemSecurityHandler problemSecurityHandler,
		CorsConfigurationSource corsConfigurationSource
	) throws Exception {
		http
			.cors(cors -> cors.configurationSource(corsConfigurationSource))
			.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(cache -> cache.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(problemSecurityHandler)
				.accessDeniedHandler(problemSecurityHandler))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers(
					HttpMethod.GET,
					"/v3/api-docs",
					"/v3/api-docs.yaml",
					"/v3/api-docs/**",
					"/swagger-ui.html",
					"/swagger-ui/**"
				).permitAll()
				.requestMatchers(
					HttpMethod.GET,
					"/api/v1/auth/login-id-availability"
				).permitAll()
				.requestMatchers(
					HttpMethod.POST,
					"/api/v1/auth/signup",
					"/api/v1/auth/login",
					"/api/v1/auth/refresh"
				).permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/tax/countries").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/tax/eligibility").permitAll()
				.requestMatchers(
					"/api/v1/me/**",
					"/api/v1/auth/logout",
					"/api/v1/auth/logout-all"
				).authenticated()
				.requestMatchers(HttpMethod.GET, "/api/v1/disclosures").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/market/**").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/news", "/api/v1/news/**").permitAll()
				.requestMatchers(
					HttpMethod.POST,
					"/api/v1/news/{articleId}/translation",
					"/api/v1/news/{articleId}/term-explanations"
				).permitAll()
				.requestMatchers(
					HttpMethod.GET,
					"/api/v1/disclosures/{receiptNumber:[0-9]{14}}",
					"/api/v1/disclosures/{receiptNumber:[0-9]{14}}/insight"
				).permitAll()
				.requestMatchers(
					HttpMethod.POST,
					"/api/v1/disclosures/{receiptNumber:[0-9]{14}}/questions"
				).permitAll()
				.requestMatchers(
					HttpMethod.GET,
					"/api/v1/disclosures/{receiptNumber:[0-9]{14}}/sections/{sectionId}/translation"
				).permitAll()
				.requestMatchers(
					HttpMethod.POST,
					"/api/v1/disclosures/{receiptNumber:[0-9]{14}}/sections/{sectionId}/translation"
				).permitAll()
				.requestMatchers(
					HttpMethod.POST,
					"/api/v1/disclosures/{receiptNumber:[0-9]{14}}/index",
					"/api/v1/disclosures/{receiptNumber:[0-9]{14}}/insight"
				).permitAll()
				.anyRequest().denyAll())
			.addFilterBefore(bearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(
		@Value("${kmarket.cors.allowed-origins}") List<String> allowedOrigins
	) {
		var configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins.stream()
			.map(String::trim)
			.filter(origin -> !origin.isBlank())
			.toList());
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Accept", "Authorization", "Content-Type", "X-Request-ID"));
		configuration.setExposedHeaders(List.of("Retry-After", "X-Request-ID"));
		configuration.setAllowCredentials(false);
		configuration.setMaxAge(3600L);

		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}
}
