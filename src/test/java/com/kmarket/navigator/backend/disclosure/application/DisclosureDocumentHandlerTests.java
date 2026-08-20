package com.kmarket.navigator.backend.disclosure.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveStore;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveKind;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentJob;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveStatus;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGateway;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGatewayException;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartSource;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartSourceException;
import com.kmarket.navigator.backend.disclosure.application.port.StoredDocumentArchive;

import java.util.List;

class DisclosureDocumentHandlerTests {

	@Test
	void blocksAllDocumentCollectionWhenDailyLimitIsReached() {
		OpenDartGateway openDartGateway = mock(OpenDartGateway.class);
		DisclosureRepository repository = mock(DisclosureRepository.class);
		DocumentJob job = new DocumentJob("20260101000001", "005930", "삼성전자", 1);
		when(repository.claimDocumentJob(org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.of(job));
		when(openDartGateway.fetchDocuments(job.receiptNumber()))
			.thenThrow(new OpenDartGatewayException("STATUS_020"));

		boolean processed = new DisclosureDocumentHandler(openDartGateway, mock(DocumentArchiveStore.class), repository).processNext();

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
		DocumentJob job = new DocumentJob("20260101000002", "005930", "삼성전자", 1);
		when(repository.claimDocumentJob(org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.of(job));
		when(openDartGateway.fetchDocuments(job.receiptNumber()))
			.thenThrow(new OpenDartGatewayException("NETWORK_ERROR"));

		boolean processed = new DisclosureDocumentHandler(openDartGateway, mock(DocumentArchiveStore.class), repository).processNext();

		assertThat(processed).isTrue();
		verify(repository).blockOpenDartDocumentCollection(Duration.ofMinutes(15), "NETWORK_ERROR");
		verify(repository).retryDocumentJob(
			job.receiptNumber(),
			"NETWORK_ERROR",
			Duration.ofMinutes(15)
		);
	}

	@Test
	void retriesViewerNetworkFailureWithoutBlockingOpenDart() {
		OpenDartGateway openDartGateway = mock(OpenDartGateway.class);
		DisclosureRepository repository = mock(DisclosureRepository.class);
		DocumentJob job = new DocumentJob("20010324000163", "005930", "삼성전자", 3);
		when(repository.claimDocumentJob(org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.of(job));
		when(openDartGateway.fetchDocuments(job.receiptNumber()))
			.thenThrow(new OpenDartGatewayException("DART_VIEWER_NETWORK_ERROR"));

		boolean processed = new DisclosureDocumentHandler(openDartGateway, mock(DocumentArchiveStore.class), repository).processNext();

		assertThat(processed).isTrue();
		verify(repository).retryDocumentJob(
			job.receiptNumber(),
			"DART_VIEWER_NETWORK_ERROR",
			Duration.ofMinutes(15)
		);
		verify(repository, never()).blockOpenDartDocumentCollection(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void failsViewerNetworkFailureAfterMaximumAttempts() {
		OpenDartGateway openDartGateway = mock(OpenDartGateway.class);
		DisclosureRepository repository = mock(DisclosureRepository.class);
		DocumentJob job = new DocumentJob("20010324000164", "005930", "삼성전자", 5);
		when(repository.claimDocumentJob(org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.of(job));
		when(openDartGateway.fetchDocuments(job.receiptNumber()))
			.thenThrow(new OpenDartGatewayException("DART_VIEWER_NETWORK_ERROR"));

		boolean processed = new DisclosureDocumentHandler(openDartGateway, mock(DocumentArchiveStore.class), repository).processNext();

		assertThat(processed).isTrue();
		verify(repository).failDocumentJob(job.receiptNumber(), "DART_VIEWER_NETWORK_ERROR");
		verify(repository, never()).retryDocumentJob(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void marksMissingDocumentUnavailableWithoutRetry() {
		OpenDartGateway openDartGateway = mock(OpenDartGateway.class);
		DisclosureRepository repository = mock(DisclosureRepository.class);
		DocumentJob job = new DocumentJob("20000101000001", "005930", "삼성전자", 1);
		when(repository.claimDocumentJob(org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.of(job));
		when(openDartGateway.fetchDocuments(job.receiptNumber()))
			.thenThrow(new OpenDartGatewayException("STATUS_014"));

		boolean processed = new DisclosureDocumentHandler(openDartGateway, mock(DocumentArchiveStore.class), repository).processNext();

		assertThat(processed).isTrue();
		verify(repository).markDocumentUnavailable(job.receiptNumber(), "STATUS_014");
		verify(repository, never()).retryDocumentJob(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void failsSourceCorruptionWithoutRetry() {
		OpenDartGateway openDartGateway = mock(OpenDartGateway.class);
		DisclosureRepository repository = mock(DisclosureRepository.class);
		DocumentJob job = new DocumentJob("20010321000241", "005930", "삼성전자", 1);
		when(repository.claimDocumentJob(org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.of(job));
		when(openDartGateway.fetchDocuments(job.receiptNumber()))
			.thenThrow(new OpenDartGatewayException("SOURCE_TEXT_CORRUPTED"));

		boolean processed = new DisclosureDocumentHandler(openDartGateway, mock(DocumentArchiveStore.class), repository).processNext();

		assertThat(processed).isTrue();
		verify(repository).failDocumentJob(job.receiptNumber(), "SOURCE_TEXT_CORRUPTED");
		verify(repository, never()).retryDocumentJob(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void recordsRejectedSourceBeforeRetrying() {
		OpenDartGateway openDartGateway = mock(OpenDartGateway.class);
		DocumentArchiveStore archiveStore = mock(DocumentArchiveStore.class);
		DisclosureRepository repository = mock(DisclosureRepository.class);
		DocumentJob job = new DocumentJob("20010321000242", "005930", "삼성전자", 1);
		OpenDartSource source = new OpenDartSource(
			DocumentArchiveKind.OPENDART_ZIP,
			DocumentArchiveStatus.REJECTED,
			new byte[] {1, 2, 3},
			"SOURCE_TEXT_CORRUPTED"
		);
		StoredDocumentArchive archive = new StoredDocumentArchive(
			DocumentArchiveKind.OPENDART_ZIP,
			DocumentArchiveStatus.REJECTED,
			"005930_삼성전자/20010321000242.api.zip",
			"a".repeat(64),
			3,
			"SOURCE_TEXT_CORRUPTED"
		);
		when(repository.claimDocumentJob(org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.of(job));
		when(openDartGateway.fetchDocuments(job.receiptNumber()))
			.thenThrow(new OpenDartSourceException("SOURCE_TEXT_CORRUPTED", source));
		when(archiveStore.store(org.mockito.ArgumentMatchers.eq(job), org.mockito.ArgumentMatchers.any()))
			.thenReturn(List.of(archive));

		new DisclosureDocumentHandler(openDartGateway, archiveStore, repository).processNext();

		verify(repository).recordDocumentArchives(job.receiptNumber(), List.of(archive));
		verify(repository).failDocumentJob(job.receiptNumber(), "SOURCE_TEXT_CORRUPTED");
	}

	@Test
	void pausesAndRetriesWhenProviderIsUnderMaintenance() {
		OpenDartGateway openDartGateway = mock(OpenDartGateway.class);
		DisclosureRepository repository = mock(DisclosureRepository.class);
		DocumentJob job = new DocumentJob("20260101000003", "005930", "삼성전자", 5);
		when(repository.claimDocumentJob(org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.of(job));
		when(openDartGateway.fetchDocuments(job.receiptNumber()))
			.thenThrow(new OpenDartGatewayException("STATUS_800"));

		boolean processed = new DisclosureDocumentHandler(openDartGateway, mock(DocumentArchiveStore.class), repository).processNext();

		assertThat(processed).isTrue();
		verify(repository).blockOpenDartDocumentCollection(Duration.ofHours(1), "STATUS_800");
		verify(repository).retryDocumentJob(
			job.receiptNumber(),
			"STATUS_800",
			Duration.ofHours(1)
		);
	}
}
