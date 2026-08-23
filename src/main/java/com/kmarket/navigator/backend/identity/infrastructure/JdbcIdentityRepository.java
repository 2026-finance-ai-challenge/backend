package com.kmarket.navigator.backend.identity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.application.port.IdentityRepository;
import com.kmarket.navigator.backend.identity.domain.InvestorType;
import com.kmarket.navigator.backend.identity.domain.TaxVerificationStatus;
import com.kmarket.navigator.backend.identity.domain.UserAccount;

@Repository
class JdbcIdentityRepository implements IdentityRepository {

	private final JdbcClient jdbcClient;

	JdbcIdentityRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public boolean existsByLoginId(String loginId) {
		return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM user_account WHERE login_id = :loginId)")
			.param("loginId", loginId)
			.query(Boolean.class)
			.single();
	}

	@Override
	public void insert(UserAccount account, Instant termsAcceptedAt, Instant privacyAcceptedAt) {
		try {
			jdbcClient.sql("""
				INSERT INTO user_account (
				    id, login_id, password_hash, nationality, investor_type,
				    tax_verification_status, terms_accepted_at, privacy_accepted_at,
				    created_at, updated_at
				)
				VALUES (
				    :id, :loginId, :passwordHash, :nationality, :investorType,
				    :taxStatus, :termsAcceptedAt, :privacyAcceptedAt,
				    :createdAt, :createdAt
				)
				""")
				.param("id", account.id())
				.param("loginId", account.loginId())
				.param("passwordHash", account.passwordHash())
				.param("nationality", account.nationality())
				.param("investorType", account.investorType().name())
				.param("taxStatus", account.taxVerificationStatus().name())
				.param("termsAcceptedAt", atUtc(termsAcceptedAt))
				.param("privacyAcceptedAt", atUtc(privacyAcceptedAt))
				.param("createdAt", atUtc(account.createdAt()))
				.update();
		}
		catch (DuplicateKeyException exception) {
			throw new BusinessException(ErrorCode.LOGIN_ID_ALREADY_EXISTS);
		}
	}

	@Override
	public Optional<UserAccount> findActiveByLoginId(String loginId) {
		return jdbcClient.sql("""
			SELECT id, login_id, password_hash, nationality, investor_type,
			       tax_verification_status, created_at
			FROM user_account
			WHERE login_id = :loginId AND deleted_at IS NULL
			""")
			.param("loginId", loginId)
			.query(this::mapAccount)
			.optional();
	}

	@Override
	public Optional<UserAccount> findActiveById(UUID userId) {
		return jdbcClient.sql("""
			SELECT id, login_id, password_hash, nationality, investor_type,
			       tax_verification_status, created_at
			FROM user_account
			WHERE id = :userId AND deleted_at IS NULL
			""")
			.param("userId", userId)
			.query(this::mapAccount)
			.optional();
	}

	@Override
	public void updatePassword(UUID userId, String passwordHash, Instant updatedAt) {
		jdbcClient.sql("""
			UPDATE user_account
			SET password_hash = :passwordHash, updated_at = :updatedAt
			WHERE id = :userId AND deleted_at IS NULL
			""")
			.param("passwordHash", passwordHash)
			.param("updatedAt", atUtc(updatedAt))
			.param("userId", userId)
			.update();
	}

	@Override
	public void softDelete(UUID userId, Instant deletedAt) {
		jdbcClient.sql("""
			UPDATE user_account
			SET deleted_at = :deletedAt, updated_at = :deletedAt
			WHERE id = :userId AND deleted_at IS NULL
			""")
			.param("deletedAt", atUtc(deletedAt))
			.param("userId", userId)
			.update();
	}

	private UserAccount mapAccount(ResultSet resultSet, int rowNumber) throws SQLException {
		return new UserAccount(
			resultSet.getObject("id", UUID.class),
			resultSet.getString("login_id"),
			resultSet.getString("password_hash"),
			resultSet.getString("nationality"),
			InvestorType.valueOf(resultSet.getString("investor_type")),
			TaxVerificationStatus.valueOf(resultSet.getString("tax_verification_status")),
			resultSet.getObject("created_at", OffsetDateTime.class).toInstant()
		);
	}

	private OffsetDateTime atUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
