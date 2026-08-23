package com.kmarket.navigator.backend.identity.infrastructure;

import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Types;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.kmarket.navigator.backend.identity.application.ClientContext;
import com.kmarket.navigator.backend.identity.application.port.SecurityAuditRepository;

@Repository
class JdbcSecurityAuditRepository implements SecurityAuditRepository {

	private final JdbcClient jdbcClient;

	JdbcSecurityAuditRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public void record(
		UUID userId,
		String eventType,
		String subjectType,
		String subjectId,
		ClientContext context,
		Instant createdAt
	) {
		JdbcClient.StatementSpec statement = jdbcClient.sql("""
			INSERT INTO security_audit_event (
			    id, user_id, event_type, subject_type, subject_id, request_id,
			    client_ip_hash, user_agent_hash, created_at
			)
			VALUES (
			    :id, :userId, :eventType, :subjectType, :subjectId, :requestId,
			    :clientIpHash, :userAgentHash, :createdAt
			)
			""")
			.param("id", UUID.randomUUID())
			.param("eventType", eventType)
			.param("clientIpHash", context.ipHash())
			.param("userAgentHash", context.userAgentHash())
			.param("createdAt", createdAt.atOffset(ZoneOffset.UTC));
		statement = nullable(statement, "userId", userId, Types.OTHER);
		statement = nullable(statement, "subjectType", subjectType, Types.VARCHAR);
		statement = nullable(statement, "subjectId", subjectId, Types.VARCHAR);
		statement = nullable(statement, "requestId", context.requestId(), Types.VARCHAR);
		statement.update();
	}

	private <T> JdbcClient.StatementSpec nullable(
		JdbcClient.StatementSpec statement,
		String name,
		T value,
		int sqlType
	) {
		return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
	}
}
