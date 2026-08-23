package com.kmarket.navigator.backend.identity.presentation;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.kmarket.navigator.backend.identity.application.IdentityService;

@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

	private static final int MAX_AUTHORIZATION_HEADER_LENGTH = 512;
	private final IdentityService identityService;

	public BearerTokenAuthenticationFilter(IdentityService identityService) {
		this.identityService = identityService;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String authorization = request.getHeader("Authorization");
		if (authorization != null
			&& authorization.length() <= MAX_AUTHORIZATION_HEADER_LENGTH
			&& authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			String accessToken = authorization.substring(7).trim();
			identityService.authenticate(accessToken).ifPresent(user -> {
				UsernamePasswordAuthenticationToken authentication =
					UsernamePasswordAuthenticationToken.authenticated(
						user,
						null,
						java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))
					);
				SecurityContextHolder.getContext().setAuthentication(authentication);
			});
		}
		filterChain.doFilter(request, response);
	}
}
