package com.kmarket.navigator.backend.disclosure.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentJob;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGateway;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGatewayException;

class DisclosureDocumentHandlerTests {

	@Test
	void blocksAllDocumentCollectionWhenDailyLimitIsReached() {
		OpenDartGateway openDartGateway = mock(OpenDartGateway.class);
		DisclosureRepository repository = mock(DisclosureRepository.class);
		DocumentJob job = new DocumentJob("20260101000001", 1);
		when(repository.claimDocumentJob(org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.of(job));
		when(openDartGateway.fetchDocuments(job.receiptNumber()))
			.thenThrow(new OpenDartGatewayException("STATUS_020"));

		boolean processed = new DisclosureDocumentHandler(openDartGateway, repository).processNext();

		assertThat(processed).isTrue();
		verify(repository).blockOpenDartDocumentCollection(Duration.ofHours(24), "STATUS_020");
		verify(repository).retryDocumentJob(
			job.receiptNumber(),
			"STATUS_020",
			Duration.ofHours(24)
		);
	}

	@Test
	void pausesDocumentCollectionAfterProviderNetworkFailure() {
		OpenDartGateway openDartGateway = mock(OpenDartGateway.class);
		DisclosureRepository repository = mock(DisclosureRepository.class);
		DocumentJob job = new DocumentJob("20260101000002", 1);
		when(repository.claimDocumentJob(org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.of(job));
		when(openDartGateway.fetchDocuments(job.receiptNumber()))
			.thenThrow(new OpenDartGatewayException("NETWORK_ERROR"));

		boolean processed = new DisclosureDocumentHandler(openDartGateway, repository).processNext();

		assertThat(processed).isTrue();
		verify(repository).blockOpenDartDocumentCollection(Duration.ofMinutes(15), "NETWORK_ERROR");
		verify(repository).retryDocumentJob(
			job.receiptNumber(),
			"NETWORK_ERROR",
			Duration.ofMinutes(15)
		);
	}
}
