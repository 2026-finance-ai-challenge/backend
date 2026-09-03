package com.kmarket.navigator.backend.disclosure.application.port;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureInsight;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSummary;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSignalJob;
import com.kmarket.navigator.backend.disclosure.domain.DocumentStatus;
import com.kmarket.navigator.backend.disclosure.domain.IndexStatus;
import com.kmarket.navigator.backend.disclosure.domain.ListedCommonStock;
import com.kmarket.navigator.backend.global.text.EnglishTextPolicy;

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

	Optional<DisclosureSignalJob> claimSignalJob(String workerId);

	void completeSignalJob(String receiptNumber, com.kmarket.navigator.backend.news.domain.NewsAnalysis analysis);

	void retrySignalJob(String receiptNumber, String errorCode, Duration delay);

	List<DisclosureSummary> findAll(DisclosureListQuery query, int fetchSize);

	Optional<DisclosureDetail> findByReceiptNumber(String receiptNumber);

	default Optional<DisclosureDetail> findPublishedByReceiptNumber(String receiptNumber) {
		return findByReceiptNumber(receiptNumber).filter(detail ->
			detail.documentStatus() == DocumentStatus.READY
				&& EnglishTextPolicy.isValid(detail.titleEn())
				&& detail.eventType() != null
				&& detail.sentiment() != null
				&& detail.importance() != null
				&& detail.marketImpact() != null
		);
	}

	Optional<DisclosureInsight> findInsight(String receiptNumber, String contentVersionHash);

	void saveInsight(DisclosureInsight insight);

	Optional<IndexStatus> findIndexStatus(String receiptNumber);

	boolean requestIndexing(String receiptNumber);
}
