package com.kmarket.navigator.backend.tax.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.tax.domain.TaxDocument;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentFields;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentIssue;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentStatus;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentVerification;

@Component
public class TaxDocumentVerificationPolicy {

	private static final BigDecimal HIGH_TAMPER_RISK = new BigDecimal("0.8000");
	private final Clock clock = Clock.systemUTC();

	public TaxDocumentVerification validate(
		TaxDocument document,
		TaxDocumentVerification generated,
		List<TaxDocumentFields> comparableFields
	) {
		Set<String> missing = new LinkedHashSet<>(generated.missingRequiredFields());
		Map<String, TaxDocumentIssue> issues = new LinkedHashMap<>();
		generated.issues().forEach(issue -> issues.put(issue.code(), issue));
		TaxDocumentStatus status = generated.status();

		if (generated.detectedDocumentType() != document.documentType()) {
			status = TaxDocumentStatus.REJECTED;
			issues.put("DOCUMENT_TYPE_MISMATCH", issue(
				"DOCUMENT_TYPE_MISMATCH",
				"HIGH",
				"The uploaded file does not match the selected document type."
			));
		}
		for (String field : required(document.documentType())) {
			if (blank(value(generated.fields(), field))) {
				missing.add(field);
			}
		}
		if (!missing.isEmpty() && status != TaxDocumentStatus.REJECTED) {
			status = TaxDocumentStatus.REVIEW_REQUIRED;
		}
		String documentCountry = country(document.documentType(), generated.fields());
		if (!blank(documentCountry)
			&& !document.expectedResidencyCountry().equalsIgnoreCase(documentCountry)
			&& status != TaxDocumentStatus.REJECTED) {
			status = TaxDocumentStatus.REVIEW_REQUIRED;
			issues.put("RESIDENCY_COUNTRY_MISMATCH", issue(
				"RESIDENCY_COUNTRY_MISMATCH",
				"HIGH",
				"The document country does not match the selected tax residence."
			));
		}
		status = expiryStatus(generated.fields().expiryDate(), status, issues);
		if (generated.tamperRisk() != null
			&& generated.tamperRisk().compareTo(HIGH_TAMPER_RISK) >= 0
			&& status != TaxDocumentStatus.REJECTED) {
			status = TaxDocumentStatus.REVIEW_REQUIRED;
			issues.put("HIGH_TAMPER_RISK_SIGNAL", issue(
				"HIGH_TAMPER_RISK_SIGNAL",
				"HIGH",
				"The automated screening detected visual consistency signals that require human review."
			));
		}
		if (inconsistentWithOtherDocuments(generated.fields(), comparableFields)
			&& status != TaxDocumentStatus.REJECTED) {
			status = TaxDocumentStatus.REVIEW_REQUIRED;
			issues.put("CROSS_DOCUMENT_MISMATCH", issue(
				"CROSS_DOCUMENT_MISMATCH",
				"HIGH",
				"The holder or country differs from another uploaded tax document."
			));
		}
		return new TaxDocumentVerification(
			generated.detectedDocumentType(),
			status,
			generated.fields(),
			List.copyOf(missing),
			List.copyOf(issues.values()),
			generated.ocrConfidence(),
			generated.tamperRisk(),
			generated.manualReviewRequired() || status != TaxDocumentStatus.VERIFIED,
			generated.modelId(),
			generated.promptVersion()
		);
	}

	private TaxDocumentStatus expiryStatus(
		String expiryDate,
		TaxDocumentStatus current,
		Map<String, TaxDocumentIssue> issues
	) {
		if (blank(expiryDate)) {
			return current;
		}
		try {
			if (LocalDate.parse(expiryDate).isBefore(LocalDate.now(clock))) {
				issues.put("DOCUMENT_EXPIRED", issue(
					"DOCUMENT_EXPIRED",
					"HIGH",
					"The document expiry date has passed."
				));
				return TaxDocumentStatus.REJECTED;
			}
			return current;
		}
		catch (DateTimeParseException exception) {
			issues.put("INVALID_EXPIRY_DATE", issue(
				"INVALID_EXPIRY_DATE",
				"WARNING",
				"The expiry date could not be validated."
			));
			return current == TaxDocumentStatus.REJECTED
				? current
				: TaxDocumentStatus.REVIEW_REQUIRED;
		}
	}

	private boolean inconsistentWithOtherDocuments(
		TaxDocumentFields fields,
		List<TaxDocumentFields> comparableFields
	) {
		String holder = normalize(fields.holderName());
		String country = firstNonBlank(
			fields.residencyCountry(),
			fields.apostilleCountry(),
			fields.treatyCountry()
		);
		return comparableFields.stream().anyMatch(other -> {
			String otherHolder = normalize(other.holderName());
			String otherCountry = firstNonBlank(
				other.residencyCountry(),
				other.apostilleCountry(),
				other.treatyCountry()
			);
			return (!holder.isBlank() && !otherHolder.isBlank() && !holder.equals(otherHolder))
				|| (!blank(country) && !blank(otherCountry) && !country.equalsIgnoreCase(otherCountry));
		});
	}

	private List<String> required(TaxDocumentType type) {
		return switch (type) {
			case RESIDENCY_CERTIFICATE -> List.of(
				"holder_name", "residency_country", "issue_date", "issuing_authority"
			);
			case APOSTILLE -> List.of(
				"apostille_country", "issue_date", "document_number"
			);
			case REDUCED_TAX_APPLICATION -> List.of(
				"holder_name", "treaty_country", "investor_type"
			);
			case UNKNOWN -> List.of();
		};
	}

	private String value(TaxDocumentFields fields, String field) {
		return switch (field) {
			case "holder_name" -> fields.holderName();
			case "residency_country" -> fields.residencyCountry();
			case "issue_date" -> fields.issueDate();
			case "issuing_authority" -> fields.issuingAuthority();
			case "apostille_country" -> fields.apostilleCountry();
			case "document_number" -> fields.documentNumber();
			case "treaty_country" -> fields.treatyCountry();
			case "investor_type" -> fields.investorType();
			default -> null;
		};
	}

	private String country(TaxDocumentType type, TaxDocumentFields fields) {
		return switch (type) {
			case RESIDENCY_CERTIFICATE -> fields.residencyCountry();
			case APOSTILLE -> fields.apostilleCountry();
			case REDUCED_TAX_APPLICATION -> fields.treatyCountry();
			case UNKNOWN -> null;
		};
	}

	private TaxDocumentIssue issue(String code, String severity, String message) {
		return new TaxDocumentIssue(code, severity, message);
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (!blank(value)) {
				return value;
			}
		}
		return null;
	}

	private String normalize(String value) {
		return value == null ? "" : value.replaceAll("[^\\p{L}\\p{N}]", "")
			.toLowerCase(Locale.ROOT);
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
