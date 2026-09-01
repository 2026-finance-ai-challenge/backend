package com.kmarket.navigator.backend.tax.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.disclosure.infrastructure.ai.AiServiceProperties;
import com.kmarket.navigator.backend.identity.domain.InvestorType;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentFields;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentReviewInput;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentStatus;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;

class AiTaxDocumentClientTests {

	@Test
	void sendsOnlyAuthenticatedValidatedDocumentContract() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		AiServiceProperties properties = new AiServiceProperties();
		properties.setServiceToken("test-service-token");
		AiTaxDocumentClient client = new AiTaxDocumentClient(builder.build(), properties);

		server.expect(requestTo(containsString("/internal/v1/tax/documents/verify")))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-service-token"))
			.andExpect(content().json("""
				{
				  "document_type": "RESIDENCY_CERTIFICATE",
				  "file_name": "certificate.pdf",
				  "content_type": "application/pdf",
				  "document_base64": "JVBERi0xLjcKJSVFT0Y=",
				  "expected_residency_country": "US",
				  "investor_type": "INDIVIDUAL",
				  "safety_identifier": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
				}
				"""))
			.andRespond(withSuccess("""
				{
				  "detected_document_type": "RESIDENCY_CERTIFICATE",
				  "verification_status": "VERIFIED",
				  "fields": {
				    "holder_name": "Jane Investor",
				    "residency_country": "US",
				    "issue_date": "2026-01-10",
				    "expiry_date": null,
				    "issuing_authority": "IRS",
				    "document_number": "CERT-100",
				    "apostille_country": null,
				    "treaty_country": null,
				    "investor_type": "INDIVIDUAL"
				  },
				  "missing_required_fields": [],
				  "issues": [],
				  "ocr_confidence": 0.97,
				  "tamper_risk": 0.02,
				  "manual_review_required": false,
				  "model": "kmarket-tax-document-ocr-runtime-v2",
				  "prompt_version": "tax-document-v2"
				}
				""", MediaType.APPLICATION_JSON));

		var result = client.verify(
			TaxDocumentType.RESIDENCY_CERTIFICATE,
			"certificate.pdf",
			"application/pdf",
			"%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII),
			"US",
			InvestorType.INDIVIDUAL,
			"a".repeat(64)
		);

		assertThat(result.status()).isEqualTo(TaxDocumentStatus.VERIFIED);
		assertThat(result.fields().residencyCountry()).isEqualTo("US");
		server.verify();
	}

	@Test
	void sendsThreeDocumentBundleToAuthenticatedComparisonContract() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		AiServiceProperties properties = new AiServiceProperties();
		properties.setServiceToken("test-service-token");
		AiTaxDocumentClient client = new AiTaxDocumentClient(builder.build(), properties);

		server.expect(requestTo(containsString("/internal/v1/tax/documents/compare")))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-service-token"))
			.andExpect(content().string(containsString("\"document_type\":\"APOSTILLE\"")))
			.andExpect(content().string(containsString("\"expected_residency_country\":\"US\"")))
			.andExpect(content().string(containsString("\"holder_name\":\"Maria L. Chen\"")))
			.andExpect(content().string(not(containsString("document_base64"))))
			.andRespond(withSuccess("""
				{
				  "verification_status": "VERIFIED",
				  "findings": [],
				  "cross_check": {"matched": true, "reason": null},
				  "documents": [
				    {
				      "detected_document_type": "RESIDENCY_CERTIFICATE",
				      "verification_status": "VERIFIED",
				      "fields": {
				        "holder_name": "Maria L. Chen",
				        "residency_country": "US",
				        "issue_date": "2026-01-12",
				        "expiry_date": null,
				        "issuing_authority": "IRS",
				        "document_number": "987-65-4321",
				        "apostille_country": null,
				        "treaty_country": null,
				        "investor_type": null
				      },
				      "missing_required_fields": [],
				      "issues": [],
				      "ocr_confidence": 0.97,
				      "tamper_risk": 0.03,
				      "manual_review_required": false,
				      "model": "kmarket-tax-document-ocr-runtime-v2",
				      "prompt_version": "tax-document-v2"
				    }
				  ],
				  "model": "kmarket-tax-document-ocr-runtime-v2"
				}
				""", MediaType.APPLICATION_JSON));

		var result = client.compare(
			List.of(
				cachedResult(
					TaxDocumentType.RESIDENCY_CERTIFICATE,
					new TaxDocumentFields(
						"Maria L. Chen", "US", "2026-01-12", null, "IRS",
						"987-65-4321", null, null, null
					)
				),
				cachedResult(
					TaxDocumentType.APOSTILLE,
					new TaxDocumentFields(
						"Chong U Choi", null, "2014-04-10", null, "Secretary of State",
						"1185973223", "US", null, null
					)
				),
				cachedResult(
					TaxDocumentType.REDUCED_TAX_APPLICATION,
					new TaxDocumentFields(
						"Maria L Chen", null, "2026-01-12", null, null,
						"987-65-4321", null, "US", "INDIVIDUAL"
					)
				)
			),
			"US",
			InvestorType.INDIVIDUAL,
			"a".repeat(64)
		);

		assertThat(result.verificationStatus()).isEqualTo("VERIFIED");
		assertThat(result.crossCheck()).containsEntry("matched", true);
		server.verify();
	}

	private TaxDocumentReviewInput cachedResult(
		TaxDocumentType documentType,
		TaxDocumentFields fields
	) {
		return new TaxDocumentReviewInput(
			documentType,
			documentType,
			TaxDocumentStatus.VERIFIED,
			fields,
			List.of(),
			List.of(),
			new BigDecimal("0.95"),
			new BigDecimal("0.05"),
			false,
			"kmarket-tax-document-ocr-runtime-v2",
			"tax-document-v2"
		);
	}
}
