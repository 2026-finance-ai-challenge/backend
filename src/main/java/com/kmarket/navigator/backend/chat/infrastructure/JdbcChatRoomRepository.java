package com.kmarket.navigator.backend.chat.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.kmarket.navigator.backend.chat.application.port.ChatRoomRepository;
import com.kmarket.navigator.backend.chat.domain.ChatContext;
import com.kmarket.navigator.backend.chat.domain.ChatContextType;
import com.kmarket.navigator.backend.chat.domain.ChatRoom;

@Repository
public class JdbcChatRoomRepository implements ChatRoomRepository {

	private static final String ROOM_COLUMNS = """
		id, user_id, name, context_type, context_reference_id, context_version,
		context_title, version, created_at, updated_at, last_message_at
		""";
	private final JdbcClient jdbcClient;

	public JdbcChatRoomRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public ChatRoom create(UUID userId, String name, ChatContext context, Instant now) {
		UUID id = UUID.randomUUID();
		JdbcClient.StatementSpec statement = jdbcClient.sql("""
			INSERT INTO chat_room (
			    id, user_id, name, context_type, context_reference_id, context_version,
			    context_title, version, created_at, updated_at, last_message_at
			)
			VALUES (
			    :id, :userId, :name, :contextType, :contextReferenceId, :contextVersion,
			    :contextTitle, 0, :now, :now, :now
			)
			""")
			.param("id", id)
			.param("userId", userId)
			.param("name", name)
			.param("contextType", context.type().name())
			.param("now", now.atOffset(ZoneOffset.UTC));
		statement = nullable(statement, "contextReferenceId", context.referenceId());
		statement = nullable(statement, "contextVersion", context.version());
		statement = nullable(statement, "contextTitle", context.title());
		statement.update();
		return findOwned(userId, id).orElseThrow();
	}

	@Override
	public List<ChatRoom> findAll(UUID userId, String query, int limit) {
		if (query == null) {
			return jdbcClient.sql("SELECT " + ROOM_COLUMNS + """
				 FROM chat_room
				 WHERE user_id = :userId AND deleted_at IS NULL
				 ORDER BY last_message_at DESC, id DESC
				 LIMIT :limit
				 """)
				.param("userId", userId)
				.param("limit", limit)
				.query(this::mapRoom)
				.list();
		}
		return jdbcClient.sql("SELECT " + ROOM_COLUMNS + """
			 FROM chat_room
			 WHERE user_id = :userId AND deleted_at IS NULL
			   AND position(lower(:query) in lower(name)) > 0
			 ORDER BY last_message_at DESC, id DESC
			 LIMIT :limit
			 """)
			.param("userId", userId)
			.param("query", query)
			.param("limit", limit)
			.query(this::mapRoom)
			.list();
	}

	@Override
	public Optional<ChatRoom> findOwned(UUID userId, UUID roomId) {
		return jdbcClient.sql("SELECT " + ROOM_COLUMNS + """
			 FROM chat_room
			 WHERE id = :roomId AND user_id = :userId AND deleted_at IS NULL
			 """)
			.param("roomId", roomId)
			.param("userId", userId)
			.query(this::mapRoom)
			.optional();
	}

	@Override
	public Optional<ChatRoom> rename(
		UUID userId,
		UUID roomId,
		String name,
		long expectedVersion,
		Instant now
	) {
		return jdbcClient.sql("""
			UPDATE chat_room
			SET name = :name, version = version + 1, updated_at = :now
			WHERE id = :roomId AND user_id = :userId AND deleted_at IS NULL
			  AND version = :expectedVersion
			RETURNING %s
			""".formatted(ROOM_COLUMNS))
			.param("name", name)
			.param("now", now.atOffset(ZoneOffset.UTC))
			.param("roomId", roomId)
			.param("userId", userId)
			.param("expectedVersion", expectedVersion)
			.query(this::mapRoom)
			.optional();
	}

	@Override
	public boolean softDelete(UUID userId, UUID roomId, Instant deletedAt, Instant purgeAfter) {
		return jdbcClient.sql("""
			UPDATE chat_room
			SET deleted_at = :deletedAt, purge_after = :purgeAfter,
			    version = version + 1, updated_at = :deletedAt
			WHERE id = :roomId AND user_id = :userId AND deleted_at IS NULL
			""")
			.param("deletedAt", deletedAt.atOffset(ZoneOffset.UTC))
			.param("purgeAfter", purgeAfter.atOffset(ZoneOffset.UTC))
			.param("roomId", roomId)
			.param("userId", userId)
			.update() == 1;
	}

	@Override
	public int purgeExpired(Instant now, int limit) {
		return jdbcClient.sql("""
			WITH expired AS (
			    SELECT id
			    FROM chat_room
			    WHERE purge_after <= :now
			    ORDER BY purge_after
			    LIMIT :limit
			    FOR UPDATE SKIP LOCKED
			)
			DELETE FROM chat_room room
			USING expired
			WHERE room.id = expired.id
			""")
			.param("now", now.atOffset(ZoneOffset.UTC))
			.param("limit", limit)
			.update();
	}

	private ChatRoom mapRoom(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ChatRoom(
			resultSet.getObject("id", UUID.class),
			resultSet.getObject("user_id", UUID.class),
			resultSet.getString("name"),
			new ChatContext(
				ChatContextType.valueOf(resultSet.getString("context_type")),
				resultSet.getString("context_reference_id"),
				resultSet.getString("context_version"),
				resultSet.getString("context_title")
			),
			resultSet.getLong("version"),
			resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
			resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
			resultSet.getObject("last_message_at", OffsetDateTime.class).toInstant()
		);
	}

	private JdbcClient.StatementSpec nullable(
		JdbcClient.StatementSpec statement,
		String name,
		String value
	) {
		return statement.param(name, value, java.sql.Types.VARCHAR);
	}
}
