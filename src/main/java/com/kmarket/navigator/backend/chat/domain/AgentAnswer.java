package com.kmarket.navigator.backend.chat.domain;

import java.math.BigDecimal;
import java.util.List;

public record AgentAnswer(
	String answer,
	List<String> evidenceIds,
	boolean insufficientEvidence,
	String refusalReason,
	String suggestedRoomName,
	String disclaimer,
	BigDecimal confidence,
	String modelId,
	String promptVersion
) {
	public AgentAnswer {
		evidenceIds = List.copyOf(evidenceIds);
	}
}
