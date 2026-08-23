package com.kmarket.navigator.backend.stock.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.kmarket.navigator.backend.stock.application.port.GlobalPeerAnalysisRepository;
import com.kmarket.navigator.backend.stock.domain.GlobalPeerAnalysis;

import tools.jackson.databind.ObjectMapper;

@Repository
class JdbcGlobalPeerAnalysisRepository implements GlobalPeerAnalysisRepository {

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	JdbcGlobalPeerAnalysisRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<GlobalPeerAnalysis> find(String stockCode, String dataVersion) {
		return jdbcClient.sql("""
			SELECT analysis::text
			FROM global_peer_analysis
			WHERE stock_code = :stockCode AND data_version = :dataVersion
			""")
			.param("stockCode", stockCode)
			.param("dataVersion", dataVersion)
			.query(String.class)
			.optional()
			.map(this::read);
	}

	@Override
	public void save(
		String stockCode,
		String dataVersion,
		GlobalPeerAnalysis analysis,
		Instant generatedAt
	) {
		jdbcClient.sql("""
			INSERT INTO global_peer_analysis (
			    stock_code, data_version, analysis, generated_at, updated_at
			) VALUES (
			    :stockCode, :dataVersion, CAST(:analysis AS jsonb), :generatedAt, :generatedAt
			)
			ON CONFLICT (stock_code) DO UPDATE
			SET data_version = EXCLUDED.data_version,
			    analysis = EXCLUDED.analysis,
			    generated_at = EXCLUDED.generated_at,
			    updated_at = EXCLUDED.updated_at
			""")
			.param("stockCode", stockCode)
			.param("dataVersion", dataVersion)
			.param("analysis", write(analysis))
			.param("generatedAt", Timestamp.from(generatedAt))
			.update();
	}

	private String write(GlobalPeerAnalysis analysis) {
		try {
			return objectMapper.writeValueAsString(analysis);
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException("Global peer analysis serialization failed", exception);
		}
	}

	private GlobalPeerAnalysis read(String value) {
		try {
			return objectMapper.readValue(value, GlobalPeerAnalysis.class);
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException("Global peer analysis parsing failed", exception);
		}
	}
}
