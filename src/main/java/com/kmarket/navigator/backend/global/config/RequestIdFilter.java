package com.kmarket.navigator.backend.global.config;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String ATTRIBUTE = "kmarket.requestId";
	public static final String HEADER = "X-Request-ID";
	private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String supplied = request.getHeader(HEADER);
		String requestId = supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()
			? supplied
			: UUID.randomUUID().toString();
		request.setAttribute(ATTRIBUTE, requestId);
		response.setHeader(HEADER, requestId);
		filterChain.doFilter(request, response);
	}
}
