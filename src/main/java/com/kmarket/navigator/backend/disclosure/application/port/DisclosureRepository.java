package com.kmarket.navigator.backend.disclosure.application.port;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSummary;
import com.kmarket.navigator.backend.disclosure.domain.IndexStatus;
import com.kmarket.navigator.backend.disclosure.domain.ListedCommonStock;

public interface DisclosureRepository {

	void upsertCorporations(List<OpenDartCorporation> corporations);

	void replaceCommonStockUniverse(List<ListedCommonStock> stocks);

	Set<String> findActiveCommonStockCodes();

	boolean saveFiling(OpenDartFiling filing);

	int saveFilings(List<OpenDartFiling> filings);

	Optional<DocumentJob> claimDocumentJob(String workerId);

	boolean isOpenDartDocumentCollectionBlocked();

	void blockOpenDartDocumentCollection(Duration delay, String reason);

	void completeDocumentJob(
		String receiptNumber,
		List<OpenDartDocument> documents,
		List<StoredDocumentArchive> archives
	);

	void recordDocumentArchives(String receiptNumber, List<StoredDocumentArchive> archives);

	void retryDocumentJob(String receiptNumber, String errorCode, Duration delay);

	void failDocumentJob(String receiptNumber, String errorCode);

	void markDocumentUnavailable(String receiptNumber, String errorCode);

	List<DisclosureSummary> findAll(DisclosureListQuery query, int fetchSize);

	Optional<DisclosureDetail> findByReceiptNumber(String receiptNumber);

	Optional<IndexStatus> findIndexStatus(String receiptNumber);

	boolean requestIndexing(String receiptNumber);
}
