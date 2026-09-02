package com.kmarket.navigator.backend.chat.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.chat.application.port.AgentGateway;
import com.kmarket.navigator.backend.chat.application.port.ChatMessageRepository;
import com.kmarket.navigator.backend.chat.domain.ChatContext;
import com.kmarket.navigator.backend.chat.domain.ChatContextType;
import com.kmarket.navigator.backend.disclosure.application.DisclosureQuestionHandler;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

class ChatGenerationWorkerTests {
	@Test
	void failureIsTerminalWithoutAutomaticRegeneration() {
		var repository = mock(ChatMessageRepository.class);
		var provider = mock(AgentEvidenceProvider.class);
		var gateway = mock(AgentGateway.class);
		var safety = mock(AgentSafetyIdentifier.class);
		var context = new ChatContext(ChatContextType.GENERAL, null, null, "Market");
		var task = new ChatGenerationTask(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			UUID.randomUUID(), null, 1, "Latest 005930 news", null, null, context, List.of(), "en");
		when(repository.claim(anyString(), anyInt(), any(), any())).thenReturn(List.of(task));
		when(provider.evidence(context, task.question())).thenReturn(List.of());
		when(safety.from(task.userId())).thenReturn("a".repeat(64));
		when(gateway.answer(any(), anyString(), any(), any(), anyString(), anyString()))
			.thenThrow(new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE));
		var worker = new ChatGenerationWorker(repository, gateway, provider, safety,
			mock(DisclosureQuestionHandler.class), mock(DisclosureRepository.class),
			mock(com.kmarket.navigator.backend.translation.application.DisclosureSelectionValidator.class));

		worker.process();

		verify(provider).evidence(context, task.question());
		verify(repository).fail(eq(task.generationId()), eq("AI_SERVICE_UNAVAILABLE"), eq(true), any(), any());
	}
}
