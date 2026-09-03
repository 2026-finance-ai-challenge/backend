package com.kmarket.navigator.backend.tax.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kmarket.navigator.backend.identity.application.port.IdentityRepository;
import com.kmarket.navigator.backend.identity.domain.TaxVerificationStatus;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentRepository;
import com.kmarket.navigator.backend.tax.domain.TaxDocument;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentStatus;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

@Service
public class TaxReviewPackageService {
	private final IdentityRepository users;
	private final TaxDocumentRepository documents;
	public TaxReviewPackageService(IdentityRepository users, TaxDocumentRepository documents) {
		this.users = users; this.documents = documents;
	}
	@Transactional(readOnly = true)
	public List<TaxDocument> verifiedDocuments(UUID userId) {
		var user = users.findActiveById(userId).orElseThrow(() -> new BusinessException(ErrorCode.TAX_DOCUMENT_NOT_FOUND));
		if (user.taxVerificationStatus() != TaxVerificationStatus.VERIFIED) throw new BusinessException(ErrorCode.TAX_DOCUMENT_STEP_BLOCKED);
		var available = documents.findAll(userId);
		return List.of(TaxDocumentType.RESIDENCY_CERTIFICATE, TaxDocumentType.APOSTILLE, TaxDocumentType.REDUCED_TAX_APPLICATION)
			.stream().map(type -> available.stream().filter(d -> d.documentType() == type && d.status() == TaxDocumentStatus.VERIFIED
				&& !d.storageKey().startsWith("purged/")).findFirst()
				.orElseThrow(() -> new BusinessException(ErrorCode.TAX_DOCUMENT_NOT_FOUND))).toList();
	}
}
