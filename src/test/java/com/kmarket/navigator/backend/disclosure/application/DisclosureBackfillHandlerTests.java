package com.kmarket.navigator.backend.disclosure.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureBackfillRepository;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureBackfillJob;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureBackfillStatus;

class DisclosureBackfillHandlerTests {

	private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
	private static final LocalDate TO = LocalDate.of(2026, 8, 18);

	@Test
	void resumesFromCheckpointAndAdvancesAfterEachQuarter() {
		DisclosureCollectionHandler collectionHandler = mock(DisclosureCollectionHandler.class);
		DisclosureBackfillRepository repository = mock(DisclosureBackfillRepository.class);
		UUID jobId = UUID.randomUUID();
		when(repository.startOrResume(
			org.mockito.ArgumentMatchers.eq(FROM),
			org.mockito.ArgumentMatchers.eq(TO),
			org.mockito.ArgumentMatchers.any()
		))
			.thenAnswer(invocation -> new DisclosureBackfillJob(
				jobId,
				FROM,
				TO,
				FROM.plusMonths(3),
				DisclosureBackfillStatus.RUNNING,
				invocation.getArgument(2),
				4
			));
		LocalDate resumedFrom = FROM.plusMonths(3);
		LocalDate firstPeriodEnd = resumedFrom.plusMonths(3).minusDays(1);
		when(collectionHandler.collect(resumedFrom, firstPeriodEnd)).thenReturn(2);
		when(collectionHandler.collect(firstPeriodEnd.plusDays(1), TO)).thenReturn(3);

		var result = new DisclosureBackfillHandler(collectionHandler, repository).run(FROM, TO);

		assertThat(result.collectedCount()).isEqualTo(9);
		assertThat(result.alreadyCompleted()).isFalse();
		InOrder order = inOrder(collectionHandler, repository);
		order.verify(collectionHandler).synchronizeCorporations();
		order.verify(collectionHandler).collect(resumedFrom, firstPeriodEnd);
		order.verify(repository).advance(
			org.mockito.ArgumentMatchers.eq(jobId),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq(resumedFrom),
			org.mockito.ArgumentMatchers.eq(firstPeriodEnd),
			org.mockito.ArgumentMatchers.eq(2)
		);
		order.verify(collectionHandler).collect(firstPeriodEnd.plusDays(1), TO);
		order.verify(repository).advance(
			org.mockito.ArgumentMatchers.eq(jobId),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq(firstPeriodEnd.plusDays(1)),
			org.mockito.ArgumentMatchers.eq(TO),
			org.mockito.ArgumentMatchers.eq(3)
		);
		order.verify(repository).complete(
			org.mockito.ArgumentMatchers.eq(jobId),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void completedRangeDoesNotCallOpenDartAgain() {
		DisclosureCollectionHandler collectionHandler = mock(DisclosureCollectionHandler.class);
		DisclosureBackfillRepository repository = mock(DisclosureBackfillRepository.class);
		when(repository.startOrResume(
			org.mockito.ArgumentMatchers.eq(FROM),
			org.mockito.ArgumentMatchers.eq(TO),
			org.mockito.ArgumentMatchers.any()
		))
			.thenReturn(new DisclosureBackfillJob(
				UUID.randomUUID(),
				FROM,
				TO,
				TO.plusDays(1),
				DisclosureBackfillStatus.COMPLETED,
				null,
				10
			));

		var result = new DisclosureBackfillHandler(collectionHandler, repository).run(FROM, TO);

		assertThat(result.alreadyCompleted()).isTrue();
		assertThat(result.collectedCount()).isEqualTo(10);
		verify(collectionHandler, never()).synchronizeCorporations();
	}

	@Test
	void failureKeepsNextDateForResume() {
		DisclosureCollectionHandler collectionHandler = mock(DisclosureCollectionHandler.class);
		DisclosureBackfillRepository repository = mock(DisclosureBackfillRepository.class);
		UUID jobId = UUID.randomUUID();
		when(repository.startOrResume(
			org.mockito.ArgumentMatchers.eq(FROM),
			org.mockito.ArgumentMatchers.eq(TO),
			org.mockito.ArgumentMatchers.any()
		))
			.thenAnswer(invocation -> new DisclosureBackfillJob(
				jobId,
				FROM,
				TO,
				FROM,
				DisclosureBackfillStatus.RUNNING,
				invocation.getArgument(2),
				0
			));
		when(collectionHandler.collect(FROM, FROM.plusMonths(3).minusDays(1)))
			.thenThrow(new IllegalStateException("failed"));

		assertThatThrownBy(() -> new DisclosureBackfillHandler(collectionHandler, repository).run(FROM, TO))
			.isInstanceOf(IllegalStateException.class);
		verify(repository).fail(
			org.mockito.ArgumentMatchers.eq(jobId),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq("IllegalStateException")
		);
	}
}
