package com.kmarket.navigator.backend.disclosure.application;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentJob;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGateway;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGatewayException;

@Service
public class DisclosureDocumentHandler {

	private static final Logger log = LoggerFactory.getLogger(DisclosureDocumentHandler.class);
	private static final int MAX_ATTEMPTS = 5;
	private static final Duration DAILY_LIMIT_RETRY_DELAY = Duration.ofHours(24);
	private static final Duration NETWORK_ERROR_RETRY_DELAY = Duration.ofMinutes(15);

	private final OpenDartGateway openDartGateway;
	private final DisclosureRepository disclosureRepository;
	private final String workerId = UUID.randomUUID().toString();

	public DisclosureDocumentHandler(
		OpenDartGateway openDartGateway,
		DisclosureRepository disclosureRepository
	) {
		this.openDartGateway = openDartGateway;
		this.disclosureRepository = disclosureRepository;
	}

	public boolean processNext() {
		var claimed = disclosureRepository.claimDocumentJob(workerId);
		if (claimed.isEmpty()) {
			return false;
		}
		DocumentJob job = claimed.get();

		try {
			disclosureRepository.completeDocumentJob(
				job.receiptNumber(),
				openDartGateway.fetchDocuments(job.receiptNumber())
			);
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
		else if (job.attempts() >= MAX_ATTEMPTS) {
			if (errorCode.equals("STATUS_014")) {
				disclosureRepository.markDocumentUnavailable(job.receiptNumber(), errorCode);
			}
			else {
				disclosureRepository.failDocumentJob(job.receiptNumber(), errorCode);
			}
		}
		else {
			disclosureRepository.retryDocumentJob(job.receiptNumber(), errorCode, Duration.ofMinutes(5));
		}
		log.warn("공시 원문 처리 실패: receiptNumber={}, errorType={}", job.receiptNumber(), errorCode);
	}
}
