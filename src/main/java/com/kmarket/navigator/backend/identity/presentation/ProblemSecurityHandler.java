package com.kmarket.navigator.backend.identity.presentation;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.global.config.RequestIdFilter;

import tools.jackson.databind.ObjectMapper;

@Component
public class ProblemSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	public ProblemSecurityHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException exception
	) throws IOException {
		write(request, response, 401, "AUTHENTICATION_REQUIRED", "Authentication is required.");
	}

	@Override
	public void handle(
		HttpServletRequest request,
		HttpServletResponse response,
		AccessDeniedException exception
	) throws IOException {
		write(request, response, 403, "FORBIDDEN", "You do not have permission to access this resource.");
	}

	private void write(
		HttpServletRequest request,
		HttpServletResponse response,
		int status,
		String code,
		String detail
	) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", "about:blank");
		body.put("title", code);
		body.put("status", status);
		body.put("detail", detail);
		body.put("instance", request.getRequestURI());
		body.put("code", code);
		body.put("timestamp", Instant.now());
		body.put("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
		objectMapper.writeValue(response.getOutputStream(), body);
	}
}
