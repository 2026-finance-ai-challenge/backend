package com.kmarket.navigator.backend.chat.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.chat.application.port.ChatMessageRepository;
import com.kmarket.navigator.backend.chat.domain.ChatContextType;
import com.kmarket.navigator.backend.chat.domain.ChatGeneration;
import com.kmarket.navigator.backend.chat.domain.ChatMessage;
import com.kmarket.navigator.backend.chat.domain.ChatSubmission;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;

@Service
public class ChatMessageService {

	private final ChatRoomService roomService;
	private final ChatMessageRepository repository;
	private final ChatRateLimiter rateLimiter;
	private final Clock clock;

	@Autowired
	public ChatMessageService(
		ChatRoomService roomService,
		ChatMessageRepository repository,
		ChatRateLimiter rateLimiter
	) {
		this(roomService, repository, rateLimiter, Clock.systemUTC());
	}

	ChatMessageService(
		ChatRoomService roomService,
		ChatMessageRepository repository,
		ChatRateLimiter rateLimiter,
		Clock clock
	) {
		this.roomService = roomService;
		this.repository = repository;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
	}

	@Transactional
	public ChatSubmission submit(
		AuthenticatedUser user,
		UUID roomId,
		UUID clientMessageId,
		String requestedContent,
		UUID selectedSectionId,
		String selectedText
	) {
		var room = roomService.findOne(user, roomId);
		if (room.context().type() == ChatContextType.FILING && content(requestedContent).length() > 2_000) {
			throw new BusinessException(ErrorCode.INVALID_CHAT_MESSAGE);
		}
		validateSelection(room.context().type(), selectedSectionId, selectedText);
		rateLimiter.check(user.id());
		return repository.submit(
			user.id(),
			roomId,
			clientMessageId,
			content(requestedContent),
			selectedSectionId,
			selectedText == null ? null : selectedText.strip(),
			Instant.now(clock)
		);
	}

	@Transactional(readOnly = true)
	public List<ChatMessage> messages(
		AuthenticatedUser user,
		UUID roomId,
		long afterSequence,
		int limit
	) {
		roomService.findOne(user, roomId);
		return repository.findMessages(user.id(), roomId, afterSequence, limit);
	}

	@Transactional(readOnly = true)
	public ChatGeneration generation(AuthenticatedUser user, UUID roomId, UUID generationId) {
		return repository.findGeneration(user.id(), roomId, generationId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_GENERATION_NOT_FOUND));
	}

	@Transactional
	public ChatGeneration stop(AuthenticatedUser user, UUID roomId, UUID generationId) {
		roomService.findOne(user, roomId);
		if (!repository.stop(user.id(), roomId, generationId, Instant.now(clock))) {
			throw new BusinessException(ErrorCode.CHAT_GENERATION_NOT_STOPPABLE);
		}
		return generation(user, roomId, generationId);
	}

	@Transactional
	public ChatGeneration retry(AuthenticatedUser user, UUID roomId, UUID generationId) {
		roomService.findOne(user, roomId);
		if (!generation(user, roomId, generationId).retryable()) {
			throw new BusinessException(ErrorCode.CHAT_GENERATION_NOT_RETRYABLE);
		}
		rateLimiter.check(user.id());
		if (!repository.retry(user.id(), roomId, generationId, Instant.now(clock))) {
			throw new BusinessException(ErrorCode.CHAT_GENERATION_NOT_RETRYABLE);
		}
		return generation(user, roomId, generationId);
	}

	private String content(String requestedContent) {
		String normalized = requestedContent == null ? "" : requestedContent.strip();
		boolean invalidControl = normalized.codePoints()
			.anyMatch(value -> Character.isISOControl(value) && value != '\n' && value != '\t');
		if (normalized.isEmpty() || normalized.length() > 4_000 || invalidControl) {
			throw new BusinessException(ErrorCode.INVALID_CHAT_MESSAGE);
		}
		return normalized;
	}

	private void validateSelection(
		ChatContextType contextType,
		UUID selectedSectionId,
		String selectedText
	) {
		boolean hasId = selectedSectionId != null;
		boolean hasText = selectedText != null && !selectedText.isBlank();
		if (hasId != hasText
			|| (hasId && contextType != ChatContextType.FILING)
			|| (hasText && selectedText.strip().length() > 6_000)) {
			throw new BusinessException(ErrorCode.INVALID_CHAT_SELECTION);
		}
	}
}
