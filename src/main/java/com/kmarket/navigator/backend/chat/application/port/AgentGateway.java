package com.kmarket.navigator.backend.chat.application.port;

import java.util.List;

import com.kmarket.navigator.backend.chat.domain.AgentAnswer;
import com.kmarket.navigator.backend.chat.domain.AgentEvidence;
import com.kmarket.navigator.backend.chat.domain.AgentHistoryMessage;
import com.kmarket.navigator.backend.chat.domain.ChatContext;

public interface AgentGateway {

	AgentAnswer answer(
		ChatContext context,
		String question,
		List<AgentHistoryMessage> history,
		List<AgentEvidence> evidence,
		String safetyIdentifier,
		String answerLocale
	);
}
