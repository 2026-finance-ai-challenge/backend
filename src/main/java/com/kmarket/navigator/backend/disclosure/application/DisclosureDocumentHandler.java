package com.kmarket.navigator.backend.disclosure.application;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentJob;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGateway;

@Service
public class DisclosureDocumentHandler {

	private static final Logger log = LoggerFactory.getLogger(DisclosureDocumentHandler.class);
	private static final int MAX_ATTEMPTS = 5;

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
		String errorCode = exception.getClass().getSimpleName();
		if (job.attempts() >= MAX_ATTEMPTS) {
			disclosureRepository.failDocumentJob(job.receiptNumber(), errorCode);
		}
		else {
			disclosureRepository.retryDocumentJob(job.receiptNumber(), errorCode, Duration.ofMinutes(5));
		}
		log.warn("공시 원문 처리 실패: receiptNumber={}, errorType={}", job.receiptNumber(), errorCode);
	}
}
