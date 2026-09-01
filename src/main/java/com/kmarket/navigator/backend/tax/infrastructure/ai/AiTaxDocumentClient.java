package com.kmarket.navigator.backend.tax.infrastructure.ai;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.kmarket.navigator.backend.disclosure.infrastructure.ai.AiServiceProperties;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.domain.InvestorType;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentGateway;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentComparison;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentFields;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentIssue;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentReviewInput;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentStatus;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentVerification;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Component
class AiTaxDocumentClient implements TaxDocumentGateway {

	private final RestClient restClient;
	private final AiServiceProperties properties;

	AiTaxDocumentClient(
		@Qualifier("aiServiceRestClient") RestClient restClient,
		AiServiceProperties properties
	) {
		this.restClient = restClient;
		this.properties = properties;
	}

	@Override
	public TaxDocumentVerification verify(
		TaxDocumentType documentType,
		String fileName,
		String mediaType,
		byte[] content,
		String expectedResidencyCountry,
		InvestorType investorType,
		String safetyIdentifier
	) {
		if (properties.serviceToken() == null || properties.serviceToken().isBlank()) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		try {
			Response response = restClient.post()
				.uri("/internal/v1/tax/documents/verify")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
				.body(new Request(
					documentType.name(),
					fileName,
					mediaType,
					encodeBase64Copy(content),
					expectedResidencyCountry,
					investorType.name(),
					safetyIdentifier
				))
				.retrieve()
				.body(Response.class);
			if (response == null) {
				throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
			}
			return response.toDomain();
		}
		catch (RestClientException | IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
	}

	@Override
	public TaxDocumentComparison compare(
		List<TaxDocumentReviewInput> documents,
		String expectedResidencyCountry,
		InvestorType investorType,
		String safetyIdentifier
	) {
		if (properties.serviceToken() == null || properties.serviceToken().isBlank()) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		try {
			ComparisonResponse response = restClient.post()
				.uri("/internal/v1/tax/documents/compare")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
				.body(new ComparisonRequest(
					documents.stream().map(document -> new ComparisonDocument(
						document.documentType().name(),
						document.detectedDocumentType().name(),
						document.status().name(),
						Fields.from(document.fields()),
						document.missingRequiredFields(),
						document.issues(),
						document.ocrConfidence(),
						document.tamperRisk(),
						document.manualReviewRequired(),
						document.modelId(),
						document.promptVersion()
					)).toList(),
					expectedResidencyCountry,
					investorType.name(),
					safetyIdentifier
				))
				.retrieve()
				.body(ComparisonResponse.class);
			if (response == null) {
				throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
			}
			return response.toDomain();
		}
		catch (RestClientException | IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
	}

	private static String encodeBase64Copy(byte[] content) {
		byte[] copy = content.clone();
		try {
			return Base64.getEncoder().encodeToString(copy);
		}
		finally {
			Arrays.fill(copy, (byte) 0);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Request(
		String documentType,
		String fileName,
		String contentType,
		String documentBase64,
		String expectedResidencyCountry,
		String investorType,
		String safetyIdentifier
	) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record ComparisonDocument(
		String documentType,
		String detectedDocumentType,
		String verificationStatus,
		Fields fields,
		List<String> missingRequiredFields,
		List<TaxDocumentIssue> issues,
		BigDecimal ocrConfidence,
		BigDecimal tamperRisk,
		boolean manualReviewRequired,
		String model,
		String promptVersion
	) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record ComparisonRequest(
		List<ComparisonDocument> documents,
		String expectedResidencyCountry,
		String investorType,
		String safetyIdentifier
	) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Response(
		String detectedDocumentType,
		String verificationStatus,
		Fields fields,
		List<String> missingRequiredFields,
		List<TaxDocumentIssue> issues,
		BigDecimal ocrConfidence,
		BigDecimal tamperRisk,
		boolean manualReviewRequired,
		String model,
		String promptVersion
	) {
		private TaxDocumentVerification toDomain() {
			return new TaxDocumentVerification(
				TaxDocumentType.valueOf(detectedDocumentType),
				TaxDocumentStatus.valueOf(verificationStatus),
				fields.toDomain(),
				missingRequiredFields,
				issues,
				ocrConfidence,
				tamperRisk,
				manualReviewRequired,
				model,
				promptVersion
			);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record ComparisonResponse(
		String verificationStatus,
		List<TaxDocumentIssue> findings,
		Map<String, Object> crossCheck,
		List<Response> documents,
		String model
	) {
		private TaxDocumentComparison toDomain() {
			return new TaxDocumentComparison(
				verificationStatus,
				findings,
				crossCheck,
				documents.stream().map(Response::toDomain).toList(),
				model
			);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Fields(
		String holderName,
		String residencyCountry,
		String issueDate,
		String expiryDate,
		String issuingAuthority,
		String documentNumber,
		String apostilleCountry,
		String treatyCountry,
		String investorType
	) {
		private static Fields from(TaxDocumentFields fields) {
			return new Fields(
				fields.holderName(),
				fields.residencyCountry(),
				fields.issueDate(),
				fields.expiryDate(),
				fields.issuingAuthority(),
				fields.documentNumber(),
				fields.apostilleCountry(),
				fields.treatyCountry(),
				fields.investorType()
			);
		}

		private TaxDocumentFields toDomain() {
			return new TaxDocumentFields(
				holderName,
				residencyCountry,
				issueDate,
				expiryDate,
				issuingAuthority,
				documentNumber,
				apostilleCountry,
				treatyCountry,
				investorType
			);
		}
	}
}
