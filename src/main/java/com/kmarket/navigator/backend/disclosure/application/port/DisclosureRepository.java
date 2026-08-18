package com.kmarket.navigator.backend.disclosure.application.port;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSummary;

public interface DisclosureRepository {

	void upsertCorporations(List<OpenDartCorporation> corporations);

	boolean saveFiling(OpenDartFiling filing);

	Optional<DocumentJob> claimDocumentJob(String workerId);

	void completeDocumentJob(String receiptNumber, List<OpenDartDocument> documents);

	void retryDocumentJob(String receiptNumber, String errorCode, Duration delay);

	void failDocumentJob(String receiptNumber, String errorCode);

	List<DisclosureSummary> findAll(DisclosureListQuery query, int fetchSize);

	Optional<DisclosureDetail> findByReceiptNumber(String receiptNumber);
}
