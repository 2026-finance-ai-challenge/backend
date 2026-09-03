package com.kmarket.navigator.backend.tax.presentation;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;
import com.kmarket.navigator.backend.tax.application.TaxReviewPackageService;
import com.kmarket.navigator.backend.tax.infrastructure.pdf.TaxCorrectionPreview;

@RestController
@RequestMapping("/api/v1/me/tax-review-package")
public class TaxReviewPackageController {
	private final TaxReviewPackageService service;
	private final TaxCorrectionPreview preview;
	public TaxReviewPackageController(TaxReviewPackageService service, TaxCorrectionPreview preview) { this.service = service; this.preview = preview; }
	@GetMapping
	public ResponseEntity<PackageResponse> get(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(defaultValue = "en") String locale) {
		String language = language(locale);
		var documents = service.verifiedDocuments(user.id());
		var docs = documents.stream().map(d -> new DocumentResponse(d.id(), d.documentType().name(), d.mediaType())).toList();
		boolean pending = documents.stream().anyMatch(d -> d.documentType() == com.kmarket.navigator.backend.tax.domain.TaxDocumentType.REDUCED_TAX_APPLICATION && (d.fields().previewVersion() == null || d.fields().previewVersion() == 0));
		return ResponseEntity.ok().header("Cache-Control", "no-store, private").body(new PackageResponse(docs, "/api/v1/me/tax-review-package/correction.pdf?locale=" + language, preview.fields(documents, language), pending));
	}
	@GetMapping("/correction.pdf")
	public ResponseEntity<byte[]> correction(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(defaultValue = "en") String locale) {
		return ResponseEntity.ok().header("Content-Type", "application/pdf").header("Cache-Control", "no-store, private")
			.header("Content-Disposition", "inline; filename=estimated-correction.pdf").header("X-Content-Type-Options", "nosniff")
			.body(preview.render(service.verifiedDocuments(user.id()), language(locale)));
	}
	private String language(String locale) {
		if (!locale.equals("ko") && !locale.equals("en")) throw new com.kmarket.navigator.backend.global.error.BusinessException(com.kmarket.navigator.backend.global.error.ErrorCode.INVALID_REQUEST);
		return locale;
	}
	public record DocumentResponse(UUID id, String documentType, String mediaType) { }
	public record PackageResponse(List<DocumentResponse> documents, String correctionPdfPath, List<TaxCorrectionPreview.PreviewField> previewFields, boolean fieldsRefreshing) { }
}
