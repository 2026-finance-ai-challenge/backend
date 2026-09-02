package com.kmarket.navigator.backend.chat.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kmarket.navigator.backend.chat.application.ChatGenerationTask;
import com.kmarket.navigator.backend.chat.application.CompletedChatAnswer;
import com.kmarket.navigator.backend.chat.domain.ChatGeneration;
import com.kmarket.navigator.backend.chat.domain.ChatMessage;
import com.kmarket.navigator.backend.chat.domain.ChatSubmission;

public interface ChatMessageRepository {

	ChatSubmission submit(
		UUID userId,
		UUID roomId,
		UUID requestKey,
		String content,
		UUID selectedSectionId,
		String selectedText,
		String answerLocale,
		Instant now
	);

	List<ChatMessage> findMessages(UUID userId, UUID roomId, long afterSequence, int limit);

	Optional<ChatGeneration> findGeneration(UUID userId, UUID roomId, UUID generationId);

	Optional<ChatGeneration> findLatestGeneration(UUID userId, UUID roomId);

	boolean stop(UUID userId, UUID roomId, UUID generationId, Instant now);

	boolean retry(UUID userId, UUID roomId, UUID generationId, Instant now);

	List<ChatGenerationTask> claim(String workerId, int limit, Instant now, Instant staleBefore);

	boolean complete(UUID generationId, CompletedChatAnswer answer, Instant now);

	void fail(UUID generationId, String errorCode, boolean terminal, Instant retryAt, Instant now);
}
