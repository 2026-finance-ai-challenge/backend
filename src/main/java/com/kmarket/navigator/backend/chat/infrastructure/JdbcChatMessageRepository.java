package com.kmarket.navigator.backend.chat.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.chat.application.ChatGenerationTask;
import com.kmarket.navigator.backend.chat.application.CompletedChatAnswer;
import com.kmarket.navigator.backend.chat.application.port.ChatMessageRepository;
import com.kmarket.navigator.backend.chat.domain.AgentHistoryMessage;
import com.kmarket.navigator.backend.chat.domain.ChatCitation;
import com.kmarket.navigator.backend.chat.domain.ChatContext;
import com.kmarket.navigator.backend.chat.domain.ChatContextType;
import com.kmarket.navigator.backend.chat.domain.ChatGeneration;
import com.kmarket.navigator.backend.chat.domain.ChatGenerationStatus;
import com.kmarket.navigator.backend.chat.domain.ChatMessage;
import com.kmarket.navigator.backend.chat.domain.ChatMessageRole;
import com.kmarket.navigator.backend.chat.domain.ChatSubmission;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcChatMessageRepository implements ChatMessageRepository {

	private static final String MESSAGE_COLUMNS = """
		message.id, message.room_id, message.sequence_no, message.role, message.content,
		message.reply_to_message_id, message.citations, message.insufficient_evidence,
		message.refusal_reason, message.disclaimer, message.confidence, message.model_id,
		message.prompt_version, message.request_id, message.created_at
		""";
	private static final String GENERATION_COLUMNS = """
		generation.id, generation.room_id, generation.user_message_id,
		generation.regeneration_of_message_id, generation.status, generation.attempts,
		generation.last_error_code, generation.created_at, generation.updated_at,
		generation.completed_at
		""";
	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public JdbcChatMessageRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	@Transactional
	public ChatSubmission submit(
		UUID userId,
		UUID roomId,
		UUID requestKey,
		String content,
		UUID selectedSectionId,
		String selectedText,
		Instant now
	) {
		ChatMessage message = insertOrFindUserMessage(userId, roomId, requestKey, content, now);
		if (!message.content().equals(content)) {
			throw new BusinessException(ErrorCode.CHAT_IDEMPOTENCY_CONFLICT);
		}
		insertGeneration(
			roomId,
			message.id(),
			null,
			requestKey,
			selectedSectionId,
			selectedText,
			now
		);
		jdbcClient.sql("""
			UPDATE chat_room
			SET last_message_at = greatest(last_message_at, :now), updated_at = :now
			WHERE id = :roomId AND user_id = :userId AND deleted_at IS NULL
			""")
			.param("now", offset(now))
			.param("roomId", roomId)
			.param("userId", userId)
			.update();
		return new ChatSubmission(message, findGenerationByRequest(roomId, requestKey));
	}

	@Override
	@Transactional
	public ChatGeneration regenerate(
		UUID userId,
		UUID roomId,
		UUID assistantMessageId,
		UUID requestKey,
		Instant now
	) {
		RegenerationSource source = jdbcClient.sql("""
			SELECT assistant.reply_to_message_id,
			       source_generation.selected_section_id,
			       source_generation.selected_text
			FROM chat_message assistant
			JOIN chat_room room ON room.id = assistant.room_id
			LEFT JOIN chat_generation source_generation
			       ON source_generation.assistant_message_id = assistant.id
			WHERE assistant.id = :assistantMessageId
			  AND assistant.room_id = :roomId
			  AND assistant.role = 'ASSISTANT'
			  AND room.user_id = :userId
			  AND room.deleted_at IS NULL
			""")
			.param("assistantMessageId", assistantMessageId)
			.param("roomId", roomId)
			.param("userId", userId)
			.query((resultSet, rowNumber) -> new RegenerationSource(
				resultSet.getObject("reply_to_message_id", UUID.class),
				resultSet.getObject("selected_section_id", UUID.class),
				resultSet.getString("selected_text")
			))
			.optional()
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
		insertGeneration(
			roomId,
			source.userMessageId(),
			assistantMessageId,
			requestKey,
			source.selectedSectionId(),
			source.selectedText(),
			now
		);
		return findGenerationByRequest(roomId, requestKey);
	}

	@Override
	public List<ChatMessage> findMessages(UUID userId, UUID roomId, long afterSequence, int limit) {
		return jdbcClient.sql("SELECT " + MESSAGE_COLUMNS + """
			 FROM chat_message message
			 JOIN chat_room room ON room.id = message.room_id
			 WHERE message.room_id = :roomId
			   AND room.user_id = :userId
			   AND room.deleted_at IS NULL
			   AND message.sequence_no > :afterSequence
			 ORDER BY message.sequence_no
			 LIMIT :limit
			 """)
			.param("roomId", roomId)
			.param("userId", userId)
			.param("afterSequence", afterSequence)
			.param("limit", limit)
			.query((resultSet, rowNumber) -> mapMessageRow(resultSet))
			.list();
	}

	@Override
	public Optional<ChatGeneration> findGeneration(UUID userId, UUID roomId, UUID generationId) {
		return jdbcClient.sql("SELECT " + GENERATION_COLUMNS + """
			 FROM chat_generation generation
			 JOIN chat_room room ON room.id = generation.room_id
			 WHERE generation.id = :generationId
			   AND generation.room_id = :roomId
			   AND room.user_id = :userId
			   AND room.deleted_at IS NULL
			 """)
			.param("generationId", generationId)
			.param("roomId", roomId)
			.param("userId", userId)
			.query(this::mapGeneration)
			.optional();
	}

	@Override
	public boolean stop(UUID userId, UUID roomId, UUID generationId, Instant now) {
		return jdbcClient.sql("""
			UPDATE chat_generation generation
			SET status = 'STOPPED', locked_at = NULL, locked_by = NULL,
			    updated_at = :now, completed_at = :now
			FROM chat_room room
			WHERE generation.id = :generationId
			  AND generation.room_id = :roomId
			  AND generation.room_id = room.id
			  AND room.user_id = :userId
			  AND room.deleted_at IS NULL
			  AND generation.status IN ('PENDING', 'PROCESSING')
			""")
			.param("now", offset(now))
			.param("generationId", generationId)
			.param("roomId", roomId)
			.param("userId", userId)
			.update() == 1;
	}

	@Override
	public boolean retry(UUID userId, UUID roomId, UUID generationId, Instant now) {
		return jdbcClient.sql("""
			UPDATE chat_generation generation
			SET status = 'PENDING', attempts = 0, available_at = :now,
			    locked_at = NULL, locked_by = NULL, last_error_code = NULL,
			    updated_at = :now, completed_at = NULL
			FROM chat_room room
			WHERE generation.id = :generationId
			  AND generation.room_id = :roomId
			  AND generation.room_id = room.id
			  AND room.user_id = :userId
			  AND room.deleted_at IS NULL
			  AND generation.status = 'FAILED'
			""")
			.param("now", offset(now))
			.param("generationId", generationId)
			.param("roomId", roomId)
			.param("userId", userId)
			.update() == 1;
	}

	@Override
	@Transactional
	public List<ChatGenerationTask> claim(
		String workerId,
		int limit,
		Instant now,
		Instant staleBefore
	) {
		List<UUID> claimed = jdbcClient.sql("""
			WITH candidate AS (
			    SELECT generation.id
			    FROM chat_generation generation
			    JOIN chat_room room ON room.id = generation.room_id
			    WHERE room.deleted_at IS NULL
			      AND (
			          (generation.status = 'PENDING' AND generation.available_at <= :now)
			          OR (generation.status = 'PROCESSING' AND generation.locked_at < :staleBefore)
			      )
			    ORDER BY generation.available_at, generation.created_at
			    LIMIT :limit
			    FOR UPDATE OF generation SKIP LOCKED
			)
			UPDATE chat_generation generation
			SET status = 'PROCESSING', attempts = attempts + 1,
			    locked_at = :now, locked_by = :workerId, updated_at = :now
			FROM candidate
			WHERE generation.id = candidate.id
			RETURNING generation.id
			""")
			.param("now", offset(now))
			.param("staleBefore", offset(staleBefore))
			.param("limit", limit)
			.param("workerId", workerId)
			.query(UUID.class)
			.list();
		return claimed.stream().map(this::task).toList();
	}

	@Override
	@Transactional
	public boolean complete(UUID generationId, CompletedChatAnswer answer, Instant now) {
		CompletionTarget target = jdbcClient.sql("""
			SELECT generation.room_id, generation.user_message_id
			FROM chat_generation generation
			WHERE generation.id = :generationId AND generation.status = 'PROCESSING'
			FOR UPDATE
			""")
			.param("generationId", generationId)
			.query((resultSet, rowNumber) -> new CompletionTarget(
				resultSet.getObject("room_id", UUID.class),
				resultSet.getObject("user_message_id", UUID.class)
			))
			.optional()
			.orElse(null);
		if (target == null) {
			return false;
		}
		UUID assistantId = UUID.randomUUID();
		JdbcClient.StatementSpec insert = jdbcClient.sql("""
			INSERT INTO chat_message (
			    id, room_id, role, content, reply_to_message_id, citations,
			    insufficient_evidence, refusal_reason, disclaimer, confidence,
			    model_id, prompt_version, request_id, created_at
			)
			VALUES (
			    :id, :roomId, 'ASSISTANT', :content, :replyToMessageId,
			    CAST(:citations AS jsonb), :insufficientEvidence, :refusalReason,
			    :disclaimer, :confidence, :modelId, :promptVersion, :requestId, :now
			)
			""")
			.param("id", assistantId)
			.param("roomId", target.roomId())
			.param("content", answer.content())
			.param("replyToMessageId", target.userMessageId())
			.param("citations", objectMapper.writeValueAsString(answer.citations()))
			.param("insufficientEvidence", answer.insufficientEvidence())
			.param("confidence", answer.confidence())
			.param("modelId", answer.modelId())
			.param("promptVersion", answer.promptVersion())
			.param("requestId", answer.requestId())
			.param("now", offset(now));
		insert = nullable(insert, "refusalReason", answer.refusalReason(), Types.VARCHAR);
		insert = nullable(insert, "disclaimer", answer.disclaimer(), Types.VARCHAR);
		insert.update();
		jdbcClient.sql("""
			UPDATE chat_generation
			SET status = 'COMPLETED', assistant_message_id = :assistantMessageId,
			    locked_at = NULL, locked_by = NULL, last_error_code = NULL,
			    updated_at = :now, completed_at = :now
			WHERE id = :generationId
			""")
			.param("assistantMessageId", assistantId)
			.param("now", offset(now))
			.param("generationId", generationId)
			.update();
		jdbcClient.sql("""
			UPDATE chat_room
			SET name = CASE
			        WHEN name = 'New chat' THEN :suggestedRoomName
			        ELSE name
			    END,
			    version = CASE WHEN name = 'New chat' THEN version + 1 ELSE version END,
			    last_message_at = :now, updated_at = :now
			WHERE id = :roomId AND deleted_at IS NULL
			""")
			.param("suggestedRoomName", answer.suggestedRoomName())
			.param("now", offset(now))
			.param("roomId", target.roomId())
			.update();
		return true;
	}

	@Override
	public void fail(
		UUID generationId,
		String errorCode,
		boolean terminal,
		Instant retryAt,
		Instant now
	) {
		jdbcClient.sql("""
			UPDATE chat_generation
			SET status = CASE WHEN :terminal THEN 'FAILED' ELSE 'PENDING' END,
			    available_at = :retryAt, locked_at = NULL, locked_by = NULL,
			    last_error_code = :errorCode, updated_at = :now,
			    completed_at = CASE WHEN :terminal THEN :now ELSE NULL END
			WHERE id = :generationId AND status = 'PROCESSING'
			""")
			.param("terminal", terminal)
			.param("retryAt", offset(retryAt))
			.param("errorCode", errorCode)
			.param("now", offset(now))
			.param("generationId", generationId)
			.update();
	}

	private ChatMessage insertOrFindUserMessage(
		UUID userId,
		UUID roomId,
		UUID clientMessageId,
		String content,
		Instant now
	) {
		Optional<ChatMessage> inserted = jdbcClient.sql("""
			INSERT INTO chat_message (id, room_id, role, content, client_message_id, created_at)
			SELECT :id, room.id, 'USER', :content, :clientMessageId, :now
			FROM chat_room room
			WHERE room.id = :roomId AND room.user_id = :userId AND room.deleted_at IS NULL
			ON CONFLICT (room_id, client_message_id) WHERE client_message_id IS NOT NULL DO NOTHING
			RETURNING id, room_id, sequence_no, role, content, reply_to_message_id, citations,
			          insufficient_evidence, refusal_reason, disclaimer, confidence, model_id,
			          prompt_version, request_id, created_at
			""")
			.param("id", UUID.randomUUID())
			.param("roomId", roomId)
			.param("userId", userId)
			.param("content", content)
			.param("clientMessageId", clientMessageId)
			.param("now", offset(now))
			.query((resultSet, rowNumber) -> mapMessageRow(resultSet))
			.optional();
		if (inserted.isPresent()) {
			return inserted.get();
		}
		return jdbcClient.sql("""
			SELECT message.id, message.room_id, message.sequence_no, message.role, message.content,
			       message.reply_to_message_id, message.citations, message.insufficient_evidence,
			       message.refusal_reason, message.disclaimer, message.confidence, message.model_id,
			       message.prompt_version, message.request_id, message.created_at
			FROM chat_message message
			JOIN chat_room room ON room.id = message.room_id
			WHERE message.room_id = :roomId AND message.client_message_id = :clientMessageId
			  AND room.user_id = :userId AND room.deleted_at IS NULL
			""")
			.param("roomId", roomId)
			.param("clientMessageId", clientMessageId)
			.param("userId", userId)
			.query((resultSet, rowNumber) -> mapMessageRow(resultSet))
			.optional()
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
	}

	private void insertGeneration(
		UUID roomId,
		UUID userMessageId,
		UUID regenerationOfMessageId,
		UUID requestKey,
		UUID selectedSectionId,
		String selectedText,
		Instant now
	) {
		JdbcClient.StatementSpec statement = jdbcClient.sql("""
			INSERT INTO chat_generation (
			    id, room_id, user_message_id, regeneration_of_message_id, request_key,
			    selected_section_id, selected_text, status, attempts, available_at,
			    created_at, updated_at
			)
			VALUES (
			    :id, :roomId, :userMessageId, :regenerationOfMessageId, :requestKey,
			    :selectedSectionId, :selectedText, 'PENDING', 0, :now, :now, :now
			)
			ON CONFLICT (room_id, request_key) DO NOTHING
			""")
			.param("id", UUID.randomUUID())
			.param("roomId", roomId)
			.param("userMessageId", userMessageId)
			.param("requestKey", requestKey)
			.param("now", offset(now));
		statement = nullable(statement, "regenerationOfMessageId", regenerationOfMessageId, Types.OTHER);
		statement = nullable(statement, "selectedSectionId", selectedSectionId, Types.OTHER);
		statement = nullable(statement, "selectedText", selectedText, Types.VARCHAR);
		statement.update();
	}

	private ChatGeneration findGenerationByRequest(UUID roomId, UUID requestKey) {
		return jdbcClient.sql("SELECT " + GENERATION_COLUMNS + """
			 FROM chat_generation generation
			 WHERE generation.room_id = :roomId AND generation.request_key = :requestKey
			 """)
			.param("roomId", roomId)
			.param("requestKey", requestKey)
			.query(this::mapGeneration)
			.single();
	}

	private ChatGenerationTask task(UUID generationId) {
		TaskRow row = jdbcClient.sql("""
			SELECT generation.id, generation.room_id, room.user_id,
			       generation.user_message_id, generation.regeneration_of_message_id,
			       generation.attempts, user_message.content, user_message.sequence_no,
			       generation.selected_section_id, generation.selected_text,
			       room.context_type, room.context_reference_id, room.context_version,
			       room.context_title
			FROM chat_generation generation
			JOIN chat_room room ON room.id = generation.room_id
			JOIN chat_message user_message ON user_message.id = generation.user_message_id
			WHERE generation.id = :generationId
			""")
			.param("generationId", generationId)
			.query((resultSet, rowNumber) -> new TaskRow(
				resultSet.getObject("id", UUID.class),
				resultSet.getObject("room_id", UUID.class),
				resultSet.getObject("user_id", UUID.class),
				resultSet.getObject("user_message_id", UUID.class),
				resultSet.getObject("regeneration_of_message_id", UUID.class),
				resultSet.getInt("attempts"),
				resultSet.getString("content"),
				resultSet.getLong("sequence_no"),
				resultSet.getObject("selected_section_id", UUID.class),
				resultSet.getString("selected_text"),
				new ChatContext(
					ChatContextType.valueOf(resultSet.getString("context_type")),
					resultSet.getString("context_reference_id"),
					resultSet.getString("context_version"),
					resultSet.getString("context_title")
				)
			))
			.single();
		List<AgentHistoryMessage> history = jdbcClient.sql("""
			SELECT role, content
			FROM chat_message
			WHERE room_id = :roomId AND sequence_no < :sequenceNo
			ORDER BY sequence_no DESC
			LIMIT 20
			""")
			.param("roomId", row.roomId())
			.param("sequenceNo", row.sequenceNo())
			.query((resultSet, rowNumber) -> new AgentHistoryMessage(
				ChatMessageRole.valueOf(resultSet.getString("role")),
				resultSet.getString("content")
			))
			.list();
		List<AgentHistoryMessage> chronological = new ArrayList<>(history);
		Collections.reverse(chronological);
		return new ChatGenerationTask(
			row.generationId(),
			row.roomId(),
			row.userId(),
			row.userMessageId(),
			row.regenerationOfMessageId(),
			row.attempts(),
			row.question(),
			row.selectedSectionId(),
			row.selectedText(),
			row.context(),
			chronological
		);
	}

	private ChatMessage mapMessageRow(ResultSet resultSet) throws SQLException {
		String citationJson = resultSet.getString("citations");
		List<ChatCitation> citations = citationJson == null
			? List.of()
			: objectMapper.readValue(citationJson, new TypeReference<>() { });
		return new ChatMessage(
			resultSet.getObject("id", UUID.class),
			resultSet.getObject("room_id", UUID.class),
			resultSet.getLong("sequence_no"),
			ChatMessageRole.valueOf(resultSet.getString("role")),
			resultSet.getString("content"),
			resultSet.getObject("reply_to_message_id", UUID.class),
			citations,
			resultSet.getBoolean("insufficient_evidence"),
			resultSet.getString("refusal_reason"),
			resultSet.getString("disclaimer"),
			resultSet.getBigDecimal("confidence"),
			resultSet.getString("model_id"),
			resultSet.getString("prompt_version"),
			resultSet.getString("request_id"),
			resultSet.getObject("created_at", OffsetDateTime.class).toInstant()
		);
	}

	private ChatGeneration mapGeneration(ResultSet resultSet, int rowNumber) throws SQLException {
		OffsetDateTime completedAt = resultSet.getObject("completed_at", OffsetDateTime.class);
		return new ChatGeneration(
			resultSet.getObject("id", UUID.class),
			resultSet.getObject("room_id", UUID.class),
			resultSet.getObject("user_message_id", UUID.class),
			resultSet.getObject("regeneration_of_message_id", UUID.class),
			ChatGenerationStatus.valueOf(resultSet.getString("status")),
			resultSet.getInt("attempts"),
			resultSet.getString("last_error_code"),
			resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
			resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
			completedAt == null ? null : completedAt.toInstant()
		);
	}

	private OffsetDateTime offset(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private JdbcClient.StatementSpec nullable(
		JdbcClient.StatementSpec statement,
		String name,
		Object value,
		int type
	) {
		return statement.param(name, value, type);
	}

	private record RegenerationSource(
		UUID userMessageId,
		UUID selectedSectionId,
		String selectedText
	) {
	}

	private record CompletionTarget(UUID roomId, UUID userMessageId) {
	}

	private record TaskRow(
		UUID generationId,
		UUID roomId,
		UUID userId,
		UUID userMessageId,
		UUID regenerationOfMessageId,
		int attempts,
		String question,
		long sequenceNo,
		UUID selectedSectionId,
		String selectedText,
		ChatContext context
	) {
	}
}
