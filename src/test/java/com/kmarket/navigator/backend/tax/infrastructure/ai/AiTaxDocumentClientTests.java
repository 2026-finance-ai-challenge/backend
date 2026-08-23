package com.kmarket.navigator.backend.tax.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.disclosure.infrastructure.ai.AiServiceProperties;
import com.kmarket.navigator.backend.identity.domain.InvestorType;
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
				  "model": "gpt-5-mini",
				  "prompt_version": "tax-document-v1"
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
}
