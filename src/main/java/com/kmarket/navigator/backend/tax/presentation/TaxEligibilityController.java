package com.kmarket.navigator.backend.tax.presentation;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kmarket.navigator.backend.identity.domain.InvestorType;
import com.kmarket.navigator.backend.tax.application.TaxEligibilityService;
import com.kmarket.navigator.backend.tax.application.TaxEligibilityService.SupportedCountry;
import com.kmarket.navigator.backend.tax.domain.TaxEligibilityResult;

@Validated
@RestController
@RequestMapping("/api/v1/tax")
public class TaxEligibilityController {

	private final TaxEligibilityService service;

	public TaxEligibilityController(TaxEligibilityService service) {
		this.service = service;
	}

	@GetMapping("/countries")
	public ResponseEntity<List<SupportedCountry>> countries() {
		return ResponseEntity.ok(service.countries());
	}

	@PostMapping("/eligibility")
	public ResponseEntity<TaxEligibilityResult> eligibility(@Valid @RequestBody EligibilityRequest body) {
		return ResponseEntity.ok(service.check(body.residencyCountry(), body.investorType()));
	}

	public record EligibilityRequest(
		@NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String residencyCountry,
		@NotNull InvestorType investorType
	) {
	}
}
