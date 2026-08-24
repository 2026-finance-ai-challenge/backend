package com.kmarket.navigator.backend.disclosure.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureTitleTranslationJob;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureTitleTranslationSource;

public interface DisclosureTitleTranslationRepository {

	List<DisclosureTitleTranslationJob> claimJobs(
		int limit,
		String workerId,
		Instant now,
		Instant staleBefore
	);

	void complete(
		UUID translationId,
		String translatedTitle,
		String modelId,
		String promptVersion,
		Instant generatedAt
	);

	void fail(UUID translationId, String errorCode, Instant failedAt);

	void requeueFailed(Instant availableAt);

	List<DisclosureTitleTranslationSource> findOutstandingSources();

	long countSupportedDisclosures();

	long countUniqueTitles();

	long countReadyTitles();
}
