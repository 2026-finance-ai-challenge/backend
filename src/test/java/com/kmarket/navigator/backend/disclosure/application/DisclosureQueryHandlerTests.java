package com.kmarket.navigator.backend.disclosure.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;

class DisclosureQueryHandlerTests {

	@Test
	void returnsMetadataWhileDocumentPipelineIsStillRunning() {
		DisclosureRepository repository = mock(DisclosureRepository.class);
		DisclosureDetail pendingDetail = mock(DisclosureDetail.class);
		when(repository.findByReceiptNumber("20260831900176"))
			.thenReturn(Optional.of(pendingDetail));

		DisclosureDetail result = new DisclosureQueryHandler(repository)
			.findOne("20260831900176");

		assertThat(result).isSameAs(pendingDetail);
		verify(repository).findByReceiptNumber("20260831900176");
	}
}
