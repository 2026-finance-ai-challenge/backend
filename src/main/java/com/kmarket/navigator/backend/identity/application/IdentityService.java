package com.kmarket.navigator.backend.identity.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.application.port.AuthSessionRepository;
import com.kmarket.navigator.backend.identity.application.port.IdentityRepository;
import com.kmarket.navigator.backend.identity.application.port.LoginGuardRepository;
import com.kmarket.navigator.backend.identity.application.port.SecurityAuditRepository;
import com.kmarket.navigator.backend.identity.domain.AuthSession;
import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;
import com.kmarket.navigator.backend.identity.domain.InvestorType;
import com.kmarket.navigator.backend.identity.domain.TaxVerificationStatus;
import com.kmarket.navigator.backend.identity.domain.UserAccount;
import com.kmarket.navigator.backend.identity.infrastructure.ClientContextResolver;
import com.kmarket.navigator.backend.identity.infrastructure.IdentityProperties;
import com.kmarket.navigator.backend.identity.infrastructure.JwtTokenService;
import com.kmarket.navigator.backend.identity.infrastructure.RefreshTokenService;

@Service
public class IdentityService {

	private final IdentityRepository identityRepository;
	private final AuthSessionRepository sessionRepository;
	private final LoginGuardRepository loginGuardRepository;
	private final SecurityAuditRepository auditRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenService jwtTokenService;
	private final RefreshTokenService refreshTokenService;
	private final ClientContextResolver contextResolver;
	private final IdentityProperties properties;
	private final String dummyPasswordHash;

	public IdentityService(
		IdentityRepository identityRepository,
		AuthSessionRepository sessionRepository,
		LoginGuardRepository loginGuardRepository,
		SecurityAuditRepository auditRepository,
		PasswordEncoder passwordEncoder,
		JwtTokenService jwtTokenService,
		RefreshTokenService refreshTokenService,
		ClientContextResolver contextResolver,
		IdentityProperties properties
	) {
		this.identityRepository = identityRepository;
		this.sessionRepository = sessionRepository;
		this.loginGuardRepository = loginGuardRepository;
		this.auditRepository = auditRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtTokenService = jwtTokenService;
		this.refreshTokenService = refreshTokenService;
		this.contextResolver = contextResolver;
		this.properties = properties;
		this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	@Transactional
	public UserAccount signUp(
		String loginId,
		String password,
		String passwordConfirm,
		String nationality,
		InvestorType investorType,
		boolean termsAccepted,
		boolean privacyAccepted,
		Boolean fscDisclaimerAccepted,
		ClientContext context
	) {
		if (!com.kmarket.navigator.backend.identity.domain.PasswordPolicy.isValid(password)
			|| !password.equals(passwordConfirm) || !termsAccepted || !privacyAccepted) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		String normalizedLoginId = normalizeLoginId(loginId);
		if (identityRepository.existsByLoginId(normalizedLoginId)) {
			throw new BusinessException(ErrorCode.LOGIN_ID_ALREADY_EXISTS);
		}
		Instant now = Instant.now();
		UserAccount account = new UserAccount(
			UUID.randomUUID(),
			normalizedLoginId,
			passwordEncoder.encode(password),
			nationality.toUpperCase(Locale.ROOT),
			investorType,
			TaxVerificationStatus.NOT_STARTED,
			now
		);
		identityRepository.insert(account, now, now);
		auditRepository.record(account.id(), "ACCOUNT_CREATED", "USER", account.id().toString(), context, now);
		// 구버전 가입 요청에는 동의 사실을 소급 생성하지 않는다.
		if (Boolean.TRUE.equals(fscDisclaimerAccepted)) {
			auditRepository.record(account.id(), "FSC_DISCLAIMER_ACCEPTED", "LEGAL_DOCUMENT", "fsc-disclaimer-v1", context, now);
		}
		return account;
	}

	@Transactional
	public AuthenticationResult login(String loginId, String password, ClientContext context) {
		String normalizedLoginId = normalizeLoginId(loginId);
		String guardKey = contextResolver.loginGuardKey(normalizedLoginId, context);
		Instant now = Instant.now();
		Optional<Duration> retryAfter = loginGuardRepository.retryAfter(guardKey, now);
		if (retryAfter.isPresent()) {
			long seconds = Math.max(1, retryAfter.orElseThrow().toSeconds());
			throw new BusinessException(ErrorCode.LOGIN_RATE_LIMITED, Map.of("retryAfterSeconds", seconds));
		}

		Optional<UserAccount> found = identityRepository.findActiveByLoginId(normalizedLoginId);
		String storedHash = found.map(UserAccount::passwordHash).orElse(dummyPasswordHash);
		if (!passwordEncoder.matches(password, storedHash) || found.isEmpty()) {
			loginGuardRepository.recordFailure(guardKey, now);
			auditRepository.record(null, "LOGIN_FAILED", "LOGIN", null, context, now);
			throw new BusinessException(ErrorCode.INVALID_LOGIN_CREDENTIALS);
		}

		UserAccount account = found.orElseThrow();
		loginGuardRepository.clear(guardKey);
		IssuedTokens tokens = issue(account, UUID.randomUUID(), context, now);
		auditRepository.record(account.id(), "LOGIN_SUCCEEDED", "SESSION", null, context, now);
		return new AuthenticationResult(account, tokens);
	}

	@Transactional(noRollbackFor = BusinessException.class)
	public AuthenticationResult refresh(String refreshToken, ClientContext context) {
		return refreshInternal(refreshToken, context, null);
	}

	@Transactional(noRollbackFor = BusinessException.class)
	public AuthenticationResult refreshBrowser(String refreshToken, UUID requestId, ClientContext context) {
		return refreshInternal(refreshToken, context, refreshTokenService.browserSuccessor(refreshToken, requestId));
	}

	private AuthenticationResult refreshInternal(String refreshToken, ClientContext context, String successorToken) {
		Instant now = Instant.now();
		String refreshTokenHash = refreshTokenService.hash(refreshToken);
		AuthSession oldSession = sessionRepository.findByRefreshTokenHash(refreshTokenHash)
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
		UserAccount account = identityRepository.findActiveById(oldSession.userId())
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
		PendingSession replacement = createSession(account.id(), oldSession.familyId(), context, now, successorToken);

		AuthSessionRepository.RotationResult rotation = sessionRepository.rotate(
			oldSession,
			replacement.session()
		);
		if (rotation == AuthSessionRepository.RotationResult.REPLAYED && successorToken != null) {
			AuthSession successor = sessionRepository.findByRefreshTokenHash(replacement.session().refreshTokenHash())
				.filter(session -> session.familyId().equals(oldSession.familyId()) && session.refreshActiveAt(now))
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
			return new AuthenticationResult(account, tokens(account, new PendingSession(successor, successorToken)));
		}
		if (rotation == AuthSessionRepository.RotationResult.REUSED) {
			auditRepository.record(
				oldSession.userId(),
				"REFRESH_TOKEN_REUSE_DETECTED",
				"SESSION_FAMILY",
				oldSession.familyId().toString(),
				context,
				now
			);
			throw new BusinessException(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
		}
		if (rotation != AuthSessionRepository.RotationResult.ROTATED) {
			throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
		}

		if (!oldSession.issuedIpHash().equals(context.ipHash())
			|| !oldSession.issuedUserAgentHash().equals(context.userAgentHash())) {
			auditRepository.record(
				account.id(),
				"SESSION_CONTEXT_CHANGED",
				"SESSION",
				oldSession.id().toString(),
				context,
				now
			);
		}
		auditRepository.record(
			account.id(),
			"SESSION_ROTATED",
			"SESSION",
			replacement.session().id().toString(),
			context,
			now
		);
		return new AuthenticationResult(account, tokens(account, replacement));
	}

	@Transactional
	public void logout(AuthenticatedUser user, String refreshToken, ClientContext context) {
		Instant now = Instant.now();
		String refreshTokenHash = refreshTokenService.hash(refreshToken);
		if (!sessionRepository.revoke(refreshTokenHash, user.id())) {
			throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		auditRepository.record(user.id(), "LOGOUT", "SESSION", null, context, now);
	}

	@Transactional
	public void logoutBrowser(String refreshToken, ClientContext context) {
		if (refreshToken == null || !refreshToken.matches("^kmr_[A-Za-z0-9_-]{64}$")) {
			return;
		}
		String hash = refreshTokenService.hash(refreshToken);
		sessionRepository.findByRefreshTokenHash(hash).ifPresent(session -> {
			sessionRepository.revoke(hash, session.userId());
			auditRepository.record(session.userId(), "LOGOUT", "SESSION", null, context, Instant.now());
		});
	}

	@Transactional
	public void logoutAll(AuthenticatedUser user, ClientContext context) {
		Instant now = Instant.now();
		sessionRepository.revokeAll(user.id());
		auditRepository.record(user.id(), "LOGOUT_ALL", "USER", user.id().toString(), context, now);
	}

	@Transactional(readOnly = true)
	public boolean loginIdAvailable(String loginId) {
		return !identityRepository.existsByLoginId(normalizeLoginId(loginId));
	}

	@Transactional(readOnly = true)
	public UserAccount profile(AuthenticatedUser user) {
		return identityRepository.findActiveById(user.id())
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
	}

	@Transactional
	public void changePassword(
		AuthenticatedUser user,
		String currentPassword,
		String newPassword,
		String newPasswordConfirm,
		ClientContext context
	) {
		if (!com.kmarket.navigator.backend.identity.domain.PasswordPolicy.isValid(newPassword)
			|| !newPassword.equals(newPasswordConfirm)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		UserAccount account = profile(user);
		if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
			throw new BusinessException(ErrorCode.INVALID_LOGIN_CREDENTIALS);
		}
		Instant now = Instant.now();
		identityRepository.updatePassword(user.id(), passwordEncoder.encode(newPassword), now);
		sessionRepository.revokeAll(user.id());
		auditRepository.record(user.id(), "PASSWORD_CHANGED", "USER", user.id().toString(), context, now);
	}

	@Transactional
	public void deleteAccount(AuthenticatedUser user, String password, ClientContext context) {
		UserAccount account = profile(user);
		if (!passwordEncoder.matches(password, account.passwordHash())) {
			throw new BusinessException(ErrorCode.INVALID_LOGIN_CREDENTIALS);
		}
		Instant now = Instant.now();
		sessionRepository.revokeAll(user.id());
		identityRepository.softDelete(user.id(), now);
		auditRepository.record(user.id(), "ACCOUNT_DELETED", "USER", user.id().toString(), context, now);
	}

	@Transactional(readOnly = true)
	public Optional<AuthenticatedUser> authenticate(String accessToken) {
		Instant now = Instant.now();
		return jwtTokenService.verify(accessToken)
			.filter(jwt -> jwt.expiresAt() != null && jwt.expiresAt().isAfter(now))
			.flatMap(jwt -> sessionRepository.findActiveById(jwt.sessionId())
				.filter(session -> session.userId().equals(jwt.userId()) && session.accessActiveAt(now))
				.map(session -> jwt))
			.flatMap(jwt -> identityRepository.findActiveById(jwt.userId()))
			.map(account -> new AuthenticatedUser(account.id(), account.loginId()));
	}

	private IssuedTokens issue(UserAccount account, UUID familyId, ClientContext context, Instant now) {
		PendingSession pending = createSession(account.id(), familyId, context, now);
		sessionRepository.insert(pending.session());
		return tokens(account, pending);
	}

	private PendingSession createSession(UUID userId, UUID familyId, ClientContext context, Instant now) {
		return createSession(userId, familyId, context, now, null);
	}

	private PendingSession createSession(UUID userId, UUID familyId, ClientContext context, Instant now, String successorToken) {
		String refreshToken = successorToken == null ? refreshTokenService.issue() : successorToken;
		Instant accessExpiresAt = now.plus(properties.accessTokenTtl());
		Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
		AuthSession session = new AuthSession(
			UUID.randomUUID(),
			familyId,
			userId,
			refreshTokenService.hash(refreshToken),
			context.ipHash(),
			context.userAgentHash(),
			now,
			accessExpiresAt,
			refreshExpiresAt,
			"ACTIVE",
			null
		);
		return new PendingSession(session, refreshToken);
	}

	private IssuedTokens tokens(UserAccount account, PendingSession pending) {
		JwtTokenService.IssuedJwt jwt = jwtTokenService.issue(
			account.id(),
			pending.session().id(),
			pending.session().issuedAt(),
			pending.session().accessExpiresAt()
		);
		return new IssuedTokens(
			jwt.token(),
			jwt.expiresAt(),
			pending.refreshToken(),
			pending.session().refreshExpiresAt()
		);
	}

	private String normalizeLoginId(String loginId) {
		return loginId.trim().toLowerCase(Locale.ROOT);
	}

	public record AuthenticationResult(UserAccount account, IssuedTokens tokens) {
	}

	private record PendingSession(AuthSession session, String refreshToken) {
	}
}
