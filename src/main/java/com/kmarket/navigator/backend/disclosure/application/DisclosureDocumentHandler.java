package com.kmarket.navigator.backend.disclosure.application;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveStore;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentJob;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGateway;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGatewayException;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocumentFetch;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartSourceException;

@Service
public class DisclosureDocumentHandler {

	private static final Logger log = LoggerFactory.getLogger(DisclosureDocumentHandler.class);
	private static final int MAX_ATTEMPTS = 5;
	private static final Duration DAILY_LIMIT_RETRY_DELAY = Duration.ofHours(24);
	private static final Duration NETWORK_ERROR_RETRY_DELAY = Duration.ofMinutes(15);
	private static final Duration VIEWER_NETWORK_ERROR_RETRY_DELAY = Duration.ofHours(1);
	private static final Duration VIEWER_NETWORK_ERROR_LONG_RETRY_DELAY = Duration.ofHours(6);
	private static final Duration PROVIDER_MAINTENANCE_RETRY_DELAY = Duration.ofHours(1);

	private final OpenDartGateway openDartGateway;
	private final DocumentArchiveStore documentArchiveStore;
	private final DisclosureRepository disclosureRepository;
	private final String workerId = UUID.randomUUID().toString();

	public DisclosureDocumentHandler(
		OpenDartGateway openDartGateway,
		DocumentArchiveStore documentArchiveStore,
		DisclosureRepository disclosureRepository
	) {
		this.openDartGateway = openDartGateway;
		this.documentArchiveStore = documentArchiveStore;
		this.disclosureRepository = disclosureRepository;
	}

	public boolean processNext() {
		var claimed = disclosureRepository.claimDocumentJob(workerId);
		if (claimed.isEmpty()) {
			return false;
		}
		DocumentJob job = claimed.get();

		try {
			var fetch = openDartGateway.fetchDocuments(job.receiptNumber());
			var archives = documentArchiveStore.store(job, fetch);
			disclosureRepository.completeDocumentJob(
				job.receiptNumber(),
				fetch.documents(),
				archives
			);
		}
		catch (OpenDartSourceException exception) {
			var archives = documentArchiveStore.store(
				job,
				new OpenDartDocumentFetch(java.util.List.of(), java.util.List.of(exception.source()))
			);
			disclosureRepository.recordDocumentArchives(job.receiptNumber(), archives);
			handleFailure(job, exception);
		}
		catch (RuntimeException exception) {
			handleFailure(job, exception);
		}
		return true;
	}

	private void handleFailure(DocumentJob job, RuntimeException exception) {
		String errorCode = exception instanceof OpenDartGatewayException openDartException
			? openDartException.errorCode()
			: exception.getClass().getSimpleName();
		if (errorCode.equals("STATUS_020")) {
			disclosureRepository.blockOpenDartDocumentCollection(
				DAILY_LIMIT_RETRY_DELAY,
				errorCode
			);
			disclosureRepository.retryDocumentJob(
				job.receiptNumber(),
				errorCode,
				DAILY_LIMIT_RETRY_DELAY
			);
		}
		else if (errorCode.equals("NETWORK_ERROR")) {
			disclosureRepository.blockOpenDartDocumentCollection(
				NETWORK_ERROR_RETRY_DELAY,
				errorCode
			);
			disclosureRepository.retryDocumentJob(
				job.receiptNumber(),
				errorCode,
				NETWORK_ERROR_RETRY_DELAY
			);
		}
		else if (errorCode.equals("DART_VIEWER_NETWORK_ERROR")) {
			Duration retryDelay = job.attempts() >= 3
				? (job.attempts() >= MAX_ATTEMPTS
					? VIEWER_NETWORK_ERROR_LONG_RETRY_DELAY
					: VIEWER_NETWORK_ERROR_RETRY_DELAY)
				: NETWORK_ERROR_RETRY_DELAY;
			disclosureRepository.retryDocumentJob(
				job.receiptNumber(),
				errorCode,
				retryDelay
			);
		}
		else if (errorCode.equals("STATUS_800")) {
			disclosureRepository.blockOpenDartDocumentCollection(
				PROVIDER_MAINTENANCE_RETRY_DELAY,
				errorCode
			);
			disclosureRepository.retryDocumentJob(
				job.receiptNumber(),
				errorCode,
				PROVIDER_MAINTENANCE_RETRY_DELAY
			);
		}
		else if (errorCode.equals("STATUS_014")) {
			disclosureRepository.markDocumentUnavailable(job.receiptNumber(), errorCode);
		}
		else if (errorCode.equals("SOURCE_TEXT_CORRUPTED")) {
			disclosureRepository.failDocumentJob(job.receiptNumber(), errorCode);
		}
		else if (job.attempts() >= MAX_ATTEMPTS) {
			disclosureRepository.failDocumentJob(job.receiptNumber(), errorCode);
		}
		else {
			disclosureRepository.retryDocumentJob(job.receiptNumber(), errorCode, Duration.ofMinutes(5));
		}
		log.warn("공시 원문 처리 실패: receiptNumber={}, errorType={}", job.receiptNumber(), errorCode);
	}
}
