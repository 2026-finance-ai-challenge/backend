package com.kmarket.navigator.backend.identity.presentation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.application.IdentityService;
import com.kmarket.navigator.backend.identity.application.IdentityService.AuthenticationResult;
import com.kmarket.navigator.backend.identity.infrastructure.ClientContextResolver;

@RestController
@RequestMapping("/api/v1/auth/browser")
public class BrowserIdentityController {

	static final String COOKIE_NAME = "kart_browser_refresh";
	private final IdentityService identityService;
	private final ClientContextResolver contextResolver;
	private final List<String> allowedOrigins;

	public BrowserIdentityController(IdentityService identityService, ClientContextResolver contextResolver,
		@Value("${kmarket.cors.allowed-origins}") List<String> allowedOrigins) {
		this.identityService = identityService;
		this.contextResolver = contextResolver;
		this.allowedOrigins = allowedOrigins.stream().map(String::trim).toList();
	}

	@PostMapping("/login")
	public ResponseEntity<BrowserSessionResponse> login(@Valid @RequestBody IdentityController.LoginRequest body,
		HttpServletRequest request) {
		verifyBrowserRequest(request);
		return authenticated(identityService.login(body.loginId(), body.password(), contextResolver.resolve(request)));
	}

	@PostMapping("/refresh")
	public ResponseEntity<BrowserSessionResponse> refresh(
		@Valid @RequestBody BrowserRefreshRequest body,
		@CookieValue(name = COOKIE_NAME, required = false) String refreshToken,
		HttpServletRequest request, HttpServletResponse response) {
		verifyBrowserRequest(request);
		if (refreshToken == null || !refreshToken.matches("^kmr_[A-Za-z0-9_-]{64}$")) {
			response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
			throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		try {
			return authenticated(identityService.refreshBrowser(refreshToken, body.requestId(), contextResolver.resolve(request)));
		} catch (BusinessException exception) {
			// 만료·폐기된 자격 증명만 지우고 일시적인 서버 장애에서는 쿠키를 보존한다.
			if (exception.errorCode() == ErrorCode.INVALID_REFRESH_TOKEN
				|| exception.errorCode() == ErrorCode.REFRESH_TOKEN_REUSE_DETECTED) {
				response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
			}
			throw exception;
		}
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@CookieValue(name = COOKIE_NAME, required = false) String refreshToken,
		HttpServletRequest request) {
		verifyBrowserRequest(request);
		identityService.logoutBrowser(refreshToken, contextResolver.resolve(request));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
			.header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
	}

	private void verifyBrowserRequest(HttpServletRequest request) {
		// 쿠키 인증은 신뢰한 Origin과 사전 요청이 필요한 전용 헤더를 함께 검사한다.
		if (!allowedOrigins.contains(request.getHeader(HttpHeaders.ORIGIN))
			|| !"1".equals(request.getHeader("X-KART-CSRF"))) {
			throw new BusinessException(ErrorCode.UNTRUSTED_BROWSER_REQUEST);
		}
	}

	private ResponseEntity<BrowserSessionResponse> authenticated(AuthenticationResult result) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.header(HttpHeaders.SET_COOKIE, cookie(result.tokens().refreshToken(),
				Duration.between(Instant.now(), result.tokens().refreshExpiresAt())).toString())
			.body(new BrowserSessionResponse("Bearer", result.tokens().accessToken(),
				result.tokens().accessExpiresAt(), IdentityController.ProfileResponse.from(result.account())));
	}

	private ResponseCookie cookie(String value, Duration maxAge) {
		return ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(true).sameSite("Strict")
			.path("/api/v1/auth/browser").maxAge(maxAge).build();
	}

	public record BrowserSessionResponse(String tokenType, String accessToken, Instant accessExpiresAt,
		IdentityController.ProfileResponse user) {
	}

	public record BrowserRefreshRequest(@NotNull UUID requestId) {
	}
}
