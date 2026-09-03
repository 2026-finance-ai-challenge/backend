package com.kmarket.navigator.backend.chat.application;

import java.util.List;
import java.util.UUID;

import com.kmarket.navigator.backend.chat.domain.AgentHistoryMessage;
import com.kmarket.navigator.backend.chat.domain.ChatContext;

public record ChatGenerationTask(
	UUID generationId,
	UUID roomId,
	UUID userId,
	UUID userMessageId,
	UUID regenerationOfMessageId,
	int attempts,
	String question,
	UUID selectedSectionId,
	String selectedText,
	ChatContext context,
	List<AgentHistoryMessage> history,
	String answerLocale
) {
	public ChatGenerationTask {
		history = List.copyOf(history);
	}
}
