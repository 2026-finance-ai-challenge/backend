package com.kmarket.navigator.backend.identity.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.kmarket.navigator.backend.identity.application.port.AuthSessionRepository;
import com.kmarket.navigator.backend.identity.domain.AuthSession;

@Repository
public class RedisAuthSessionRepository implements AuthSessionRepository {

	private static final String ACTIVE = "ACTIVE";
	private static final DefaultRedisScript<Long> INSERT_SCRIPT = new DefaultRedisScript<>("""
		if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then
		  return 0
		end
		redis.call('HSET', KEYS[1],
		  'id', ARGV[1],
		  'familyId', ARGV[2],
		  'userId', ARGV[3],
		  'refreshTokenHash', ARGV[4],
		  'issuedIpHash', ARGV[5],
		  'issuedUserAgentHash', ARGV[6],
		  'issuedAt', ARGV[7],
		  'accessExpiresAt', ARGV[8],
		  'refreshExpiresAt', ARGV[9],
		  'state', 'ACTIVE',
		  'replacedBySessionId', '')
		redis.call('PEXPIRE', KEYS[1], ARGV[10])
		redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[10])
		redis.call('SADD', KEYS[3], ARGV[1])
		redis.call('PEXPIRE', KEYS[3], ARGV[10])
		redis.call('SADD', KEYS[4], ARGV[1])
		redis.call('PEXPIRE', KEYS[4], ARGV[10])
		return 1
		""", Long.class);

	private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>("""
		local mapped = redis.call('GET', KEYS[2])
		if not mapped or mapped ~= ARGV[2] or redis.call('EXISTS', KEYS[1]) == 0 then
		  return 1
		end
		if redis.call('HGET', KEYS[1], 'familyId') ~= ARGV[4]
		    or redis.call('HGET', KEYS[1], 'userId') ~= ARGV[5] then
		  return 1
		end
		local state = redis.call('HGET', KEYS[1], 'state')
		if state == 'ROTATED' then
		  local familySessions = redis.call('SMEMBERS', KEYS[6])
		  for _, sessionId in ipairs(familySessions) do
		    local sessionKey = ARGV[13] .. ':session:' .. sessionId
		    local refreshHash = redis.call('HGET', sessionKey, 'refreshTokenHash')
		    redis.call('HSET', sessionKey, 'state', 'REVOKED')
		    if refreshHash then
		      redis.call('DEL', ARGV[13] .. ':refresh:' .. refreshHash)
		    end
		  end
		  return 2
		end
		if state ~= 'ACTIVE' then
		  return 3
		end
		if tonumber(redis.call('HGET', KEYS[1], 'refreshExpiresAt')) <= tonumber(ARGV[1]) then
		  redis.call('HSET', KEYS[1], 'state', 'EXPIRED')
		  redis.call('DEL', KEYS[2])
		  return 3
		end
		redis.call('HSET', KEYS[1], 'state', 'ROTATED', 'replacedBySessionId', ARGV[3])
		redis.call('HSET', KEYS[3],
		  'id', ARGV[3],
		  'familyId', ARGV[4],
		  'userId', ARGV[5],
		  'refreshTokenHash', ARGV[6],
		  'issuedIpHash', ARGV[7],
		  'issuedUserAgentHash', ARGV[8],
		  'issuedAt', ARGV[9],
		  'accessExpiresAt', ARGV[10],
		  'refreshExpiresAt', ARGV[11],
		  'state', 'ACTIVE',
		  'replacedBySessionId', '')
		redis.call('PEXPIRE', KEYS[3], ARGV[12])
		redis.call('SET', KEYS[4], ARGV[3], 'PX', ARGV[12])
		redis.call('SADD', KEYS[5], ARGV[3])
		redis.call('PEXPIRE', KEYS[5], ARGV[12])
		redis.call('SADD', KEYS[6], ARGV[3])
		redis.call('PEXPIRE', KEYS[6], ARGV[12])
		return 0
		""", Long.class);

	private static final DefaultRedisScript<Long> REVOKE_SCRIPT = new DefaultRedisScript<>("""
		local sessionId = redis.call('GET', KEYS[1])
		if not sessionId then
		  return 0
		end
		local sessionKey = ARGV[2] .. ':session:' .. sessionId
		if redis.call('HGET', sessionKey, 'userId') ~= ARGV[1] then
		  return 0
		end
		redis.call('HSET', sessionKey, 'state', 'REVOKED')
		redis.call('DEL', KEYS[1])
		return 1
		""", Long.class);

	private static final DefaultRedisScript<Long> REVOKE_ALL_SCRIPT = new DefaultRedisScript<>("""
		local sessionIds = redis.call('SMEMBERS', KEYS[1])
		for _, sessionId in ipairs(sessionIds) do
		  local sessionKey = ARGV[1] .. ':session:' .. sessionId
		  local refreshHash = redis.call('HGET', sessionKey, 'refreshTokenHash')
		  redis.call('HSET', sessionKey, 'state', 'REVOKED')
		  if refreshHash then
		    redis.call('DEL', ARGV[1] .. ':refresh:' .. refreshHash)
		  end
		end
		redis.call('DEL', KEYS[1])
		return #sessionIds
		""", Long.class);

	private final StringRedisTemplate template;
	private final String prefix;

	public RedisAuthSessionRepository(StringRedisTemplate template, IdentityProperties properties) {
		this.template = template;
		this.prefix = properties.redisKeyPrefix();
	}

	@Override
	public void insert(AuthSession session) {
		Duration ttl = remaining(session.refreshExpiresAt());
		Long inserted = template.execute(
			INSERT_SCRIPT,
			List.of(
				sessionKey(session.id()),
				refreshKey(session.refreshTokenHash()),
				userSessionsKey(session.userId()),
				familySessionsKey(session.familyId())
			),
			session.id().toString(),
			session.familyId().toString(),
			session.userId().toString(),
			session.refreshTokenHash(),
			session.issuedIpHash(),
			session.issuedUserAgentHash(),
			Long.toString(session.issuedAt().toEpochMilli()),
			Long.toString(session.accessExpiresAt().toEpochMilli()),
			Long.toString(session.refreshExpiresAt().toEpochMilli()),
			Long.toString(ttl.toMillis())
		);
		if (inserted == null || inserted != 1) {
			throw new IllegalStateException("인증 세션을 저장하지 못했습니다.");
		}
	}

	@Override
	public Optional<AuthSession> findActiveById(UUID sessionId) {
		return findSession(sessionId).filter(session -> ACTIVE.equals(session.state()));
	}

	@Override
	public Optional<AuthSession> findByRefreshTokenHash(String tokenHash) {
		String sessionId = template.opsForValue().get(refreshKey(tokenHash));
		if (sessionId == null) {
			return Optional.empty();
		}
		try {
			return findSession(UUID.fromString(sessionId));
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	@Override
	public RotationResult rotate(AuthSession oldSession, AuthSession replacement) {
		Duration ttl = remaining(replacement.refreshExpiresAt());
		Long result = template.execute(
			ROTATE_SCRIPT,
			List.of(
				sessionKey(oldSession.id()),
				refreshKey(oldSession.refreshTokenHash()),
				sessionKey(replacement.id()),
				refreshKey(replacement.refreshTokenHash()),
				userSessionsKey(replacement.userId()),
				familySessionsKey(replacement.familyId())
			),
			Long.toString(Instant.now().toEpochMilli()),
			oldSession.id().toString(),
			replacement.id().toString(),
			replacement.familyId().toString(),
			replacement.userId().toString(),
			replacement.refreshTokenHash(),
			replacement.issuedIpHash(),
			replacement.issuedUserAgentHash(),
			Long.toString(replacement.issuedAt().toEpochMilli()),
			Long.toString(replacement.accessExpiresAt().toEpochMilli()),
			Long.toString(replacement.refreshExpiresAt().toEpochMilli()),
			Long.toString(ttl.toMillis()),
			prefix
		);
		return switch (result == null ? 1 : result.intValue()) {
			case 0 -> RotationResult.ROTATED;
			case 2 -> RotationResult.REUSED;
			case 3 -> RotationResult.EXPIRED;
			default -> RotationResult.MISSING;
		};
	}

	@Override
	public boolean revoke(String refreshTokenHash, UUID userId) {
		Long result = template.execute(
			REVOKE_SCRIPT,
			List.of(refreshKey(refreshTokenHash)),
			userId.toString(),
			prefix
		);
		return result != null && result == 1;
	}

	@Override
	public void revokeAll(UUID userId) {
		template.execute(
			REVOKE_ALL_SCRIPT,
			List.of(userSessionsKey(userId)),
			prefix
		);
	}

	private Optional<AuthSession> findSession(UUID sessionId) {
		Map<String, String> values = template.<String, String>opsForHash().entries(sessionKey(sessionId));
		if (values.isEmpty()) {
			return Optional.empty();
		}
		try {
			String replacedBy = values.get("replacedBySessionId");
			return Optional.of(new AuthSession(
				UUID.fromString(values.get("id")),
				UUID.fromString(values.get("familyId")),
				UUID.fromString(values.get("userId")),
				values.get("refreshTokenHash"),
				values.get("issuedIpHash"),
				values.get("issuedUserAgentHash"),
				Instant.ofEpochMilli(Long.parseLong(values.get("issuedAt"))),
				Instant.ofEpochMilli(Long.parseLong(values.get("accessExpiresAt"))),
				Instant.ofEpochMilli(Long.parseLong(values.get("refreshExpiresAt"))),
				values.get("state"),
				replacedBy == null || replacedBy.isBlank() ? null : UUID.fromString(replacedBy)
			));
		}
		catch (IllegalArgumentException | NullPointerException exception) {
			return Optional.empty();
		}
	}

	private Duration remaining(Instant expiresAt) {
		Duration ttl = Duration.between(Instant.now(), expiresAt);
		return ttl.isNegative() || ttl.isZero() ? Duration.ofMillis(1) : ttl;
	}

	private String sessionKey(UUID sessionId) {
		return prefix + ":session:" + sessionId;
	}

	private String refreshKey(String tokenHash) {
		return prefix + ":refresh:" + tokenHash;
	}

	private String userSessionsKey(UUID userId) {
		return prefix + ":user:" + userId + ":sessions";
	}

	private String familySessionsKey(UUID familyId) {
		return prefix + ":family:" + familyId + ":sessions";
	}
}
