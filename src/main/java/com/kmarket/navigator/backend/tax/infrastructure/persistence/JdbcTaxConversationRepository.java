package com.kmarket.navigator.backend.tax.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.kmarket.navigator.backend.tax.application.port.TaxConversationRepository;
import com.kmarket.navigator.backend.tax.domain.TaxConversationState;
import com.kmarket.navigator.backend.tax.domain.TaxEligibilityResult;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentComparison;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcTaxConversationRepository implements TaxConversationRepository {
	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	public JdbcTaxConversationRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}
	public void lockUser(UUID userId) {
		jdbc.sql("SELECT id FROM user_account WHERE id = :id FOR UPDATE").param("id", userId).query(UUID.class).single();
	}
	public Optional<UUID> findRoomId(UUID userId) {
		return jdbc.sql("SELECT id FROM chat_room WHERE user_id = :id AND context_type = 'TAX_GUIDE' AND deleted_at IS NULL")
			.param("id", userId).query(UUID.class).optional();
	}
	public TaxConversationState state(UUID roomId) {
		return jdbc.sql("SELECT * FROM tax_conversation_state WHERE room_id = :id").param("id", roomId)
			.query((rs, n) -> new TaxConversationState(roomId, rs.getString("locale"),
				read(rs.getString("eligibility"), TaxEligibilityResult.class), read(rs.getString("comparison"), TaxDocumentComparison.class))).single();
	}
	public void initialize(UUID roomId, String locale) {
		jdbc.sql("INSERT INTO tax_conversation_state(room_id, locale) VALUES (:id, :locale) ON CONFLICT DO NOTHING")
			.param("id", roomId).param("locale", locale).update();
	}
	public void saveEligibility(UUID roomId, String locale, TaxEligibilityResult result) {
		jdbc.sql("UPDATE tax_conversation_state SET eligibility = CAST(:result AS jsonb), locale = :locale WHERE room_id = :id")
			.param("result", mapper.writeValueAsString(result)).param("locale", locale).param("id", roomId).update();
	}
	public void saveComparison(UUID roomId, TaxDocumentComparison result) {
		jdbc.sql("UPDATE tax_conversation_state SET comparison = CAST(:result AS jsonb) WHERE room_id = :id")
			.param("result", mapper.writeValueAsString(result)).param("id", roomId).update();
	}
	public void touch(UUID userId) {
		jdbc.sql("UPDATE chat_room SET last_message_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE user_id = :id AND context_type = 'TAX_GUIDE' AND deleted_at IS NULL")
			.param("id", userId).update();
	}
	public void deleteRoom(UUID userId, UUID roomId) {
		jdbc.sql("DELETE FROM chat_room WHERE id = :room AND user_id = :user AND context_type = 'TAX_GUIDE'")
			.param("room", roomId).param("user", userId).update();
	}
	private <T> T read(String value, Class<T> type) {
		return value == null ? null : mapper.readValue(value, type);
	}
}
