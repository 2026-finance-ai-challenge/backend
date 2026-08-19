package com.kmarket.navigator.backend.disclosure.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureBackfillRepository;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureBackfillJob;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureBackfillStatus;

@Repository
class JdbcDisclosureBackfillRepository implements DisclosureBackfillRepository {

	private static final int STALE_RUN_MINUTES = 15;

	private final JdbcClient jdbcClient;

	JdbcDisclosureBackfillRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	@Transactional
	public DisclosureBackfillJob startOrResume(LocalDate from, LocalDate to, UUID runId) {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		jdbcClient.sql("""
			INSERT INTO disclosure_backfill_job (
			    id, from_date, to_date, next_date, status, collected_count,
			    created_at, updated_at
			)
			VALUES (:id, :from, :to, :from, 'PENDING', 0, :now, :now)
			ON CONFLICT (from_date, to_date) DO NOTHING
			""")
			.param("id", UUID.randomUUID())
			.param("from", from)
			.param("to", to)
			.param("now", now)
			.update();

		jdbcClient.sql("""
			UPDATE disclosure_backfill_job
			SET status = 'RUNNING', run_id = :runId, last_error_code = NULL,
			    started_at = COALESCE(started_at, :now), updated_at = :now
			WHERE from_date = :from AND to_date = :to
			  AND (
			      status IN ('PENDING', 'FAILED')
			      OR (
			          status = 'RUNNING'
			          AND updated_at < :now - (:staleMinutes * INTERVAL '1 minute')
			      )
			  )
			""")
			.param("runId", runId)
			.param("now", now)
			.param("from", from)
			.param("to", to)
			.param("staleMinutes", STALE_RUN_MINUTES)
			.update();

		return findByRange(from, to);
	}

	@Override
	@Transactional
	public void advance(
		UUID jobId,
		UUID runId,
		LocalDate expectedNextDate,
		LocalDate processedThroughDate,
		int collectedCount
	) {
		int updated = jdbcClient.sql("""
			UPDATE disclosure_backfill_job
			SET next_date = :nextDate,
			    collected_count = collected_count + :collectedCount,
			    updated_at = CURRENT_TIMESTAMP
			WHERE id = :jobId AND run_id = :runId AND status = 'RUNNING'
			  AND next_date = :expectedNextDate
			""")
			.param("nextDate", processedThroughDate.plusDays(1))
			.param("collectedCount", collectedCount)
			.param("jobId", jobId)
			.param("runId", runId)
			.param("expectedNextDate", expectedNextDate)
			.update();
		assertUpdated(updated);
	}

	@Override
	@Transactional
	public void complete(UUID jobId, UUID runId) {
		int updated = jdbcClient.sql("""
			UPDATE disclosure_backfill_job
			SET status = 'COMPLETED', run_id = NULL, completed_at = CURRENT_TIMESTAMP,
			    updated_at = CURRENT_TIMESTAMP
			WHERE id = :jobId AND run_id = :runId AND status = 'RUNNING'
			  AND next_date = to_date + 1
			""")
			.param("jobId", jobId)
			.param("runId", runId)
			.update();
		assertUpdated(updated);
	}

	@Override
	@Transactional
	public void fail(UUID jobId, UUID runId, String errorCode) {
		jdbcClient.sql("""
			UPDATE disclosure_backfill_job
			SET status = 'FAILED', run_id = NULL, last_error_code = :errorCode,
			    updated_at = CURRENT_TIMESTAMP
			WHERE id = :jobId AND run_id = :runId AND status = 'RUNNING'
			""")
			.param("errorCode", abbreviate(errorCode, 100))
			.param("jobId", jobId)
			.param("runId", runId)
			.update();
	}

	private DisclosureBackfillJob findByRange(LocalDate from, LocalDate to) {
		return jdbcClient.sql("""
			SELECT id, from_date, to_date, next_date, status, run_id, collected_count
			FROM disclosure_backfill_job
			WHERE from_date = :from AND to_date = :to
			""")
			.param("from", from)
			.param("to", to)
			.query((resultSet, rowNumber) -> new DisclosureBackfillJob(
				resultSet.getObject("id", UUID.class),
				resultSet.getObject("from_date", LocalDate.class),
				resultSet.getObject("to_date", LocalDate.class),
				resultSet.getObject("next_date", LocalDate.class),
				DisclosureBackfillStatus.valueOf(resultSet.getString("status")),
				resultSet.getObject("run_id", UUID.class),
				resultSet.getLong("collected_count")
			))
			.single();
	}

	private static void assertUpdated(int updated) {
		if (updated != 1) {
			throw new IllegalStateException("Disclosure backfill checkpoint conflict");
		}
	}

	private static String abbreviate(String value, int maximumLength) {
		return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
	}
}
