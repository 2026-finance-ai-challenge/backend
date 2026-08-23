package com.kmarket.navigator.backend.chat.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kmarket.navigator.backend.chat.domain.ChatContext;
import com.kmarket.navigator.backend.chat.domain.ChatRoom;

public interface ChatRoomRepository {

	ChatRoom create(UUID userId, String name, ChatContext context, Instant now);

	List<ChatRoom> findAll(UUID userId, String query, int limit);

	Optional<ChatRoom> findOwned(UUID userId, UUID roomId);

	Optional<ChatRoom> rename(UUID userId, UUID roomId, String name, long expectedVersion, Instant now);

	boolean softDelete(UUID userId, UUID roomId, Instant deletedAt, Instant purgeAfter);

	int purgeExpired(Instant now, int limit);
}
