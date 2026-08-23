package com.kmarket.navigator.backend.tax.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.identity.domain.InvestorType;
import com.kmarket.navigator.backend.tax.domain.TaxEligibilityResult;

import tools.jackson.databind.ObjectMapper;

@Service
public class TaxEligibilityService {

	private static final List<String> REQUIRED_DOCUMENTS = List.of(
		"Certificate of Tax Residence",
		"Apostille",
		"Application for Reduced Withholding Tax Rate"
	);
	private final TreatyFile catalog;
	private final Map<String, TreatyRate> byCountry;

	public TaxEligibilityService(ObjectMapper objectMapper) {
		try (var input = new ClassPathResource("tax/treaty-dividend-rates.json").getInputStream()) {
			this.catalog = objectMapper.readValue(input, TreatyFile.class);
			this.byCountry = catalog.treaties().stream().collect(Collectors.toUnmodifiableMap(
				TreatyRate::countryCode,
				Function.identity()
			));
		}
		catch (IOException exception) {
			throw new IllegalStateException("Tax treaty catalog could not be loaded", exception);
		}
	}

	public TaxEligibilityResult check(String countryCode, InvestorType investorType) {
		String normalized = countryCode.toUpperCase(Locale.ROOT);
		TreatyRate rate = byCountry.get(normalized);
		if (rate == null) {
			return new TaxEligibilityResult(
				normalized,
				normalized,
				investorType,
				false,
				catalog.domesticDefaultRate(),
				null,
				null,
				null,
				catalog.asOf(),
				null,
				catalog.domesticSourceUrl(),
				REQUIRED_DOCUMENTS,
				List.of(
					"Treaty rate data is unavailable for this country in the current catalog.",
					"Confirm the applicable rate and beneficial-owner requirements with a tax adviser or broker."
				)
			);
		}
		List<String> caveats = investorType == InvestorType.CORPORATE
			&& rate.qualifyingCorporateRate() != null
			? List.of(
				"The lower corporate rate is conditional on ownership and beneficial-owner requirements.",
				"This result is informational and does not determine treaty eligibility."
			)
			: List.of(
				"The treaty rate assumes beneficial ownership and satisfaction of the treaty requirements.",
				"This result is informational and does not determine treaty eligibility."
			);
		return new TaxEligibilityResult(
			rate.countryCode(),
			rate.countryName(),
			investorType,
			true,
			catalog.domesticDefaultRate(),
			rate.generalDividendRate(),
			investorType == InvestorType.CORPORATE ? rate.qualifyingCorporateRate() : null,
			investorType == InvestorType.CORPORATE ? rate.minimumOwnershipPercent() : null,
			catalog.asOf(),
			rate.sourceUrl(),
			catalog.domesticSourceUrl(),
			REQUIRED_DOCUMENTS,
			caveats
		);
	}

	public List<SupportedCountry> countries() {
		return byCountry.values().stream()
			.map(rate -> new SupportedCountry(rate.countryCode(), rate.countryName()))
			.sorted(Comparator.comparing(SupportedCountry::countryName))
			.toList();
	}

	public record SupportedCountry(String countryCode, String countryName) {
	}

	private record TreatyFile(
		LocalDate asOf,
		BigDecimal domesticDefaultRate,
		String domesticSourceUrl,
		List<TreatyRate> treaties
	) {
	}

	private record TreatyRate(
		String countryCode,
		String countryName,
		BigDecimal generalDividendRate,
		BigDecimal qualifyingCorporateRate,
		BigDecimal minimumOwnershipPercent,
		String sourceUrl
	) {
	}
}
