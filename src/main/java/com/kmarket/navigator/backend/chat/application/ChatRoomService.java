package com.kmarket.navigator.backend.chat.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.chat.application.port.ChatRoomRepository;
import com.kmarket.navigator.backend.chat.domain.ChatContextType;
import com.kmarket.navigator.backend.chat.domain.ChatRoom;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.domain.AuthenticatedUser;

@Service
public class ChatRoomService {

	private static final Duration DELETION_RETENTION = Duration.ofDays(30);
	private final ChatRoomRepository repository;
	private final ChatContextResolver contextResolver;
	private final Clock clock;

	@Autowired
	public ChatRoomService(ChatRoomRepository repository, ChatContextResolver contextResolver) {
		this(repository, contextResolver, Clock.systemUTC());
	}

	ChatRoomService(ChatRoomRepository repository, ChatContextResolver contextResolver, Clock clock) {
		this.repository = repository;
		this.contextResolver = contextResolver;
		this.clock = clock;
	}

	@Transactional
	public ChatRoom create(AuthenticatedUser user, ChatContextType type, String referenceId) {
		var context = contextResolver.resolve(type, referenceId);
		return repository.create(user.id(), "New chat", context, Instant.now(clock));
	}

	@Transactional(readOnly = true)
	public List<ChatRoom> findAll(AuthenticatedUser user, String query, int limit) {
		return repository.findAll(user.id(), normalizeQuery(query), limit);
	}

	@Transactional(readOnly = true)
	public ChatRoom findOne(AuthenticatedUser user, UUID roomId) {
		return owned(user, roomId);
	}

	@Transactional
	public ChatRoom rename(AuthenticatedUser user, UUID roomId, String requestedName, long expectedVersion) {
		String name = normalizeName(requestedName);
		owned(user, roomId);
		return repository.rename(user.id(), roomId, name, expectedVersion, Instant.now(clock))
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_VERSION_CONFLICT));
	}

	@Transactional
	public void delete(AuthenticatedUser user, UUID roomId) {
		Instant deletedAt = Instant.now(clock);
		if (!repository.softDelete(
			user.id(),
			roomId,
			deletedAt,
			deletedAt.plus(DELETION_RETENTION)
		)) {
			throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
		}
	}

	private ChatRoom owned(AuthenticatedUser user, UUID roomId) {
		return repository.findOwned(user.id(), roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
	}

	private String normalizeQuery(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}
		return query.strip();
	}

	private String normalizeName(String requestedName) {
		String name = requestedName == null ? "" : requestedName.strip();
		if (name.isEmpty() || name.length() > 80 || name.codePoints().anyMatch(Character::isISOControl)) {
			throw new BusinessException(ErrorCode.INVALID_CHAT_ROOM_NAME);
		}
		return name;
	}
}
