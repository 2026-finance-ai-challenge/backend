package com.kmarket.navigator.backend.tax.presentation;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;
import com.kmarket.navigator.backend.identity.domain.InvestorType;
import com.kmarket.navigator.backend.tax.application.TaxConversationService;
import com.kmarket.navigator.backend.tax.domain.TaxConversationState;
import com.kmarket.navigator.backend.tax.domain.TaxGuideAction;

@RestController
@RequestMapping("/api/v1/me/tax-conversation")
public class TaxConversationController {
	private final TaxConversationService service;
	public TaxConversationController(TaxConversationService service) { this.service = service; }
	@PostMapping
	public TaxConversationState open(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody LocaleRequest request) {
		return service.get(user.id(), request.locale());
	}
	@PostMapping("/eligibility")
	public TaxConversationState assess(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody AssessmentRequest request) {
		return service.assess(user.id(), request.residencyCountry(), request.investorType(), request.locale());
	}
	@PostMapping("/restart")
	public TaxConversationState restart(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody RestartRequest request) {
		return service.restart(user.id(), request.roomId(), request.locale());
	}
	@PostMapping("/flow")
	public TaxConversationState advance(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody FlowRequest request) {
		return service.advance(user.id(), request.action());
	}
	public record LocaleRequest(@NotNull @Pattern(regexp = "en|ko") String locale) { }
	public record RestartRequest(@NotNull UUID roomId, @NotNull @Pattern(regexp = "en|ko") String locale) { }
	public record AssessmentRequest(@NotNull @Pattern(regexp = "US") String residencyCountry,
		@NotNull InvestorType investorType, @NotNull @Pattern(regexp = "en|ko") String locale) { }
	public record FlowRequest(@NotNull TaxGuideAction action) { }
}
