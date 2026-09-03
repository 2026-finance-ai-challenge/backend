package com.kmarket.navigator.backend.tax.presentation;

import java.math.BigDecimal;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;
import com.kmarket.navigator.backend.tax.application.TaxDocumentService;
import com.kmarket.navigator.backend.tax.domain.TaxDocument;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentComparison;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentFields;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentIssue;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentStatus;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;

@Validated
@RestController
@RequestMapping("/api/v1/me/tax-documents")
public class TaxDocumentController {

	private final TaxDocumentService service;

	public TaxDocumentController(TaxDocumentService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<TaxDocumentResponse> upload(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam @NotNull TaxDocumentType documentType,
		@RequestParam @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String expectedResidencyCountry,
		@RequestParam @NotNull MultipartFile file
	) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(TaxDocumentResponse.from(
			service.upload(user.id(), documentType, expectedResidencyCountry, file)
		));
	}

	@GetMapping
	public ResponseEntity<List<TaxDocumentResponse>> list(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		return ResponseEntity.ok(service.list(user.id()).stream()
			.map(TaxDocumentResponse::from)
			.toList());
	}

	@PostMapping("/comparison")
	public ResponseEntity<TaxDocumentComparisonResponse> compare(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestBody @Validated TaxDocumentComparisonRequest request
	) {
		return ResponseEntity.ok(TaxDocumentComparisonResponse.from(
			service.compare(user.id(), request.documentIds())
		));
	}

	@GetMapping("/{documentId}")
	public ResponseEntity<TaxDocumentResponse> get(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID documentId
	) {
		return ResponseEntity.ok(TaxDocumentResponse.from(service.get(user.id(), documentId)));
	}

	@GetMapping("/{documentId}/original")
	public ResponseEntity<byte[]> original(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID documentId
	) {
		var original = service.original(user.id(), documentId);
		MediaType mediaType;
		try {
			mediaType = MediaType.parseMediaType(original.mediaType());
		}
		catch (IllegalArgumentException exception) {
			mediaType = MediaType.APPLICATION_OCTET_STREAM;
		}
		return ResponseEntity.ok()
			.contentType(mediaType)
			.header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.inline()
				.filename(original.originalFileName(), StandardCharsets.UTF_8)
				.build().toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store, private")
			.header("X-Content-Type-Options", "nosniff")
			.header("Content-Security-Policy", "sandbox")
			.body(original.content());
	}

	@PostMapping("/{documentId}/retry")
	public ResponseEntity<TaxDocumentResponse> retry(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID documentId
	) {
		return ResponseEntity.ok(TaxDocumentResponse.from(service.retry(user.id(), documentId)));
	}

	@DeleteMapping("/{documentId}")
	public ResponseEntity<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable UUID documentId
	) {
		service.delete(user.id(), documentId);
		return ResponseEntity.noContent().build();
	}

	public record TaxDocumentResponse(
		UUID id,
		TaxDocumentType documentType,
		String expectedResidencyCountry,
		String investorType,
		String originalFileName,
		String mediaType,
		long sizeBytes,
		String sha256,
		TaxDocumentStatus status,
		int progress,
		String stage,
		TaxDocumentType detectedDocumentType,
		TaxDocumentFields fields,
		List<String> missingRequiredFields,
		List<TaxDocumentIssue> issues,
		BigDecimal ocrConfidence,
		BigDecimal tamperRisk,
		boolean manualReviewRequired,
		String modelId,
		String promptVersion,
		String requestId,
		String errorCode,
		Instant createdAt,
		Instant updatedAt
	) {
		private static TaxDocumentResponse from(TaxDocument document) {
			return new TaxDocumentResponse(
				document.id(),
				document.documentType(),
				document.expectedResidencyCountry(),
				document.investorType().name(),
				document.originalFileName(),
				document.mediaType(),
				document.sizeBytes(),
				document.sha256(),
				document.status(),
				document.progress(),
				document.stage(),
				document.detectedDocumentType(),
				document.fields(),
				document.missingRequiredFields(),
				document.issues(),
				document.ocrConfidence(),
				document.tamperRisk(),
				document.manualReviewRequired(),
				document.modelId(),
				document.promptVersion(),
				document.requestId(),
				document.errorCode(),
				document.createdAt(),
				document.updatedAt()
			);
		}
	}

	public record TaxDocumentComparisonRequest(
		@NotNull @Size(min = 3, max = 3) List<@NotNull UUID> documentIds
	) {
	}

	public record TaxDocumentComparisonResponse(
		String verificationStatus,
		List<TaxDocumentIssue> findings,
		java.util.Map<String, Object> crossCheck,
		List<TaxDocumentVerificationResponse> documents,
		String modelId
	) {
		private static TaxDocumentComparisonResponse from(TaxDocumentComparison comparison) {
			return new TaxDocumentComparisonResponse(
				comparison.verificationStatus(),
				comparison.findings(),
				comparison.crossCheck(),
				comparison.documents().stream()
					.map(TaxDocumentVerificationResponse::from)
					.toList(),
				comparison.modelId()
			);
		}
	}

	public record TaxDocumentVerificationResponse(
		TaxDocumentType detectedDocumentType,
		TaxDocumentStatus verificationStatus,
		TaxDocumentFields fields,
		List<String> missingRequiredFields,
		List<TaxDocumentIssue> issues,
		BigDecimal ocrConfidence,
		BigDecimal tamperRisk,
		boolean manualReviewRequired,
		String modelId,
		String promptVersion
	) {
		private static TaxDocumentVerificationResponse from(
			com.kmarket.navigator.backend.tax.domain.TaxDocumentVerification verification
		) {
			return new TaxDocumentVerificationResponse(
				verification.detectedDocumentType(),
				verification.status(),
				verification.fields(),
				verification.missingRequiredFields(),
				verification.issues(),
				verification.ocrConfidence(),
				verification.tamperRisk(),
				verification.manualReviewRequired(),
				verification.modelId(),
				verification.promptVersion()
			);
		}
	}
}
