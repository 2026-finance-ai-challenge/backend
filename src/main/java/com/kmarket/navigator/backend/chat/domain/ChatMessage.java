package com.kmarket.navigator.backend.chat.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatMessage(
	UUID id,
	UUID roomId,
	long sequence,
	ChatMessageRole role,
	String content,
	UUID replyToMessageId,
	List<ChatCitation> citations,
	boolean insufficientEvidence,
	String refusalReason,
	String disclaimer,
	BigDecimal confidence,
	String modelId,
	String promptVersion,
	String requestId,
	Instant createdAt
) {
	public ChatMessage {
		citations = List.copyOf(citations);
	}

	public ChatMessage withCitations(List<ChatCitation> localized) {
		return new ChatMessage(id, roomId, sequence, role, content, replyToMessageId, localized,
			insufficientEvidence, refusalReason, disclaimer, confidence, modelId, promptVersion, requestId, createdAt);
	}
}
