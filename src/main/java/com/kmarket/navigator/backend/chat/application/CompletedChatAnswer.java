package com.kmarket.navigator.backend.chat.application;

import java.math.BigDecimal;
import java.util.List;

import com.kmarket.navigator.backend.chat.domain.ChatCitation;

public record CompletedChatAnswer(
	String content,
	List<ChatCitation> citations,
	boolean insufficientEvidence,
	String refusalReason,
	String disclaimer,
	BigDecimal confidence,
	String modelId,
	String promptVersion,
	String suggestedRoomName,
	String requestId
) {
	public CompletedChatAnswer {
		citations = List.copyOf(citations);
	}
}
