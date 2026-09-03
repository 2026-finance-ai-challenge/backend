package com.kmarket.navigator.backend.identity.presentation;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kmarket.navigator.backend.identity.application.IdentityService;
import com.kmarket.navigator.backend.identity.application.IdentityService.AuthenticationResult;
import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;
import com.kmarket.navigator.backend.identity.domain.InvestorType;
import com.kmarket.navigator.backend.identity.domain.UserAccount;
import com.kmarket.navigator.backend.identity.infrastructure.ClientContextResolver;

@Validated
@RestController
@RequestMapping("/api/v1")
public class IdentityController {

	private static final String LOGIN_ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{3,29}$";
	private static final String TOKEN_PATTERN = "^kmr_[A-Za-z0-9_-]{64}$";
	private static final String PASSWORD_PATTERN = com.kmarket.navigator.backend.identity.domain.PasswordPolicy.PATTERN;
	private final IdentityService identityService;
	private final ClientContextResolver contextResolver;

	public IdentityController(IdentityService identityService, ClientContextResolver contextResolver) {
		this.identityService = identityService;
		this.contextResolver = contextResolver;
	}

	@GetMapping("/auth/login-id-availability")
	public LoginIdAvailabilityResponse loginIdAvailability(
		@RequestParam @Pattern(regexp = LOGIN_ID_PATTERN) String loginId
	) {
		return new LoginIdAvailabilityResponse(loginId, identityService.loginIdAvailable(loginId));
	}

	@PostMapping("/auth/signup")
	public ResponseEntity<ProfileResponse> signUp(
		@Valid @RequestBody SignUpRequest body,
		HttpServletRequest request
	) {
		UserAccount account = identityService.signUp(
			body.loginId(),
			body.password(),
			body.passwordConfirm(),
			body.nationality(),
			body.investorType(),
			body.termsAccepted(),
			body.privacyAccepted(),
			body.fscDisclaimerAccepted(),
			contextResolver.resolve(request)
		);
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(ProfileResponse.from(account));
	}

	@PostMapping("/auth/login")
	public ResponseEntity<TokenPairResponse> login(
		@Valid @RequestBody LoginRequest body,
		HttpServletRequest request
	) {
		AuthenticationResult result = identityService.login(
			body.loginId(),
			body.password(),
			contextResolver.resolve(request)
		);
		return noStore(TokenPairResponse.from(result));
	}

	@PostMapping("/auth/refresh")
	public ResponseEntity<TokenPairResponse> refresh(
		@Valid @RequestBody RefreshRequest body,
		HttpServletRequest request
	) {
		AuthenticationResult result = identityService.refresh(
			body.refreshToken(),
			contextResolver.resolve(request)
		);
		return noStore(TokenPairResponse.from(result));
	}

	@PostMapping("/auth/logout")
	public ResponseEntity<Void> logout(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody RefreshRequest body,
		HttpServletRequest request
	) {
		identityService.logout(user, body.refreshToken(), contextResolver.resolve(request));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@PostMapping("/auth/logout-all")
	public ResponseEntity<Void> logoutAll(
		@AuthenticationPrincipal AuthenticatedUser user,
		HttpServletRequest request
	) {
		identityService.logoutAll(user, contextResolver.resolve(request));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@GetMapping("/me")
	public ProfileResponse profile(@AuthenticationPrincipal AuthenticatedUser user) {
		return ProfileResponse.from(identityService.profile(user));
	}

	@PutMapping("/me/password")
	public ResponseEntity<Void> changePassword(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody ChangePasswordRequest body,
		HttpServletRequest request
	) {
		identityService.changePassword(
			user,
			body.currentPassword(),
			body.newPassword(),
			body.newPasswordConfirm(),
			contextResolver.resolve(request)
		);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@DeleteMapping("/me")
	public ResponseEntity<Void> deleteAccount(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody DeleteAccountRequest body,
		HttpServletRequest request
	) {
		identityService.deleteAccount(user, body.password(), contextResolver.resolve(request));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private ResponseEntity<TokenPairResponse> noStore(TokenPairResponse body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	public record SignUpRequest(
		@NotBlank @Pattern(regexp = LOGIN_ID_PATTERN) String loginId,
		@NotBlank @Pattern(regexp = PASSWORD_PATTERN) String password,
		@NotBlank @Pattern(regexp = PASSWORD_PATTERN) String passwordConfirm,
		@NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String nationality,
		@NotNull InvestorType investorType,
		@AssertTrue boolean termsAccepted,
		@AssertTrue boolean privacyAccepted,
		@AssertTrue Boolean fscDisclaimerAccepted
	) {
	}

	public record LoginRequest(
		@NotBlank @Pattern(regexp = LOGIN_ID_PATTERN) String loginId,
		@NotBlank @Size(min = 1, max = 128) String password
	) {
	}

	public record RefreshRequest(
		@NotBlank @Pattern(regexp = TOKEN_PATTERN) String refreshToken
	) {
	}

	public record ChangePasswordRequest(
		@NotBlank @Size(min = 1, max = 128) String currentPassword,
		@NotBlank @Pattern(regexp = PASSWORD_PATTERN) String newPassword,
		@NotBlank @Pattern(regexp = PASSWORD_PATTERN) String newPasswordConfirm
	) {
	}

	public record DeleteAccountRequest(
		@NotBlank @Size(min = 1, max = 128) String password
	) {
	}

	public record LoginIdAvailabilityResponse(String loginId, boolean available) {
	}

	public record ProfileResponse(
		String id,
		String loginId,
		String nationality,
		InvestorType investorType,
		String taxVerificationStatus,
		Instant createdAt
	) {
		static ProfileResponse from(UserAccount account) {
			return new ProfileResponse(
				account.id().toString(),
				account.loginId(),
				account.nationality(),
				account.investorType(),
				account.taxVerificationStatus().name(),
				account.createdAt()
			);
		}
	}

	public record TokenPairResponse(
		String tokenType,
		String accessToken,
		Instant accessExpiresAt,
		String refreshToken,
		Instant refreshExpiresAt,
		ProfileResponse user
	) {
		static TokenPairResponse from(AuthenticationResult result) {
			return new TokenPairResponse(
				"Bearer",
				result.tokens().accessToken(),
				result.tokens().accessExpiresAt(),
				result.tokens().refreshToken(),
				result.tokens().refreshExpiresAt(),
				ProfileResponse.from(result.account())
			);
		}
	}
}
