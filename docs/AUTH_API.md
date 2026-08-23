# Redis JWT 회원 인증 API

## 보안 모델

- 비밀번호는 OWASP 권고 파라미터의 Argon2id로 해시하며 원문을 저장하거나 로그에 남기지 않는다.
- Access Token은 HS512 서명의 15분 JWT다. `iss`, `aud`, `sub`, `sid`, `jti`, `iat`, `exp`를 검증한다.
- JWT에 로그인 ID, 국적 등 사용자 프로필을 넣지 않는다.
- 모든 인증 요청은 JWT 검증 후 Redis의 활성 세션 상태를 추가 확인한다. 로그아웃·비밀번호 변경·계정 삭제 시 기존 JWT가 즉시 무효화된다.
- Refresh Token은 384비트 난수이며 Redis에는 SHA-256 해시만 저장한다.
- Refresh Token은 사용할 때마다 회전한다. 이미 회전된 토큰이 다시 제출되면 같은 토큰 계열을 모두 폐기한다.
- 로그인 실패 횟수는 로그인 ID와 클라이언트 IP의 비가역 해시를 기준으로 Redis에서 15분 동안 관리한다. 5회 실패 후 `429`와 `Retry-After`를 반환한다.
- IP와 User-Agent는 서버 비밀 pepper를 적용한 SHA-256 해시만 감사 로그에 저장한다.

## API

| Method | Path | 인증 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/auth/login-id-availability?loginId=` | 공개 | 로그인 ID 중복 확인 |
| `POST` | `/api/v1/auth/signup` | 공개 | 계정 생성 |
| `POST` | `/api/v1/auth/login` | 공개 | Access JWT와 Refresh Token 발급 |
| `POST` | `/api/v1/auth/refresh` | 공개 | Refresh Token 회전과 새 토큰 발급 |
| `POST` | `/api/v1/auth/logout` | Bearer JWT | 현재 Refresh 세션 폐기 |
| `POST` | `/api/v1/auth/logout-all` | Bearer JWT | 사용자의 전체 세션 폐기 |
| `GET` | `/api/v1/me` | Bearer JWT | 프로필 조회 |
| `PUT` | `/api/v1/me/password` | Bearer JWT | 비밀번호 변경 후 전체 세션 폐기 |
| `DELETE` | `/api/v1/me` | Bearer JWT | 비밀번호 확인 후 계정 소프트 삭제 |

로그인과 Refresh 응답은 `Cache-Control: no-store`를 포함한다. 보호 API는 다음 헤더를 사용한다.

```http
Authorization: Bearer <access-jwt>
```

Refresh 요청 본문은 다음 형식이다.

```json
{
  "refreshToken": "kmr_<opaque-token>"
}
```

브라우저 프로토타입은 Access JWT를 메모리에만 유지한다. Refresh Token을 `localStorage`에 저장하지 않으며, 운영 프론트의 BFF 또는 동일 출처 배포에서는 `HttpOnly`, `Secure`, `SameSite=Strict` 쿠키 전달 방식으로 전환한다. 네이티브 앱은 운영체제 보안 저장소를 사용한다.

## 필수 설정

| 환경 변수 | 조건 |
| --- | --- |
| `SPRING_DATA_REDIS_HOST` | Redis 호스트 |
| `SPRING_DATA_REDIS_PORT` | Redis 포트 |
| `SPRING_DATA_REDIS_PASSWORD` | 운영 환경의 고강도 Redis 비밀번호 |
| `KMARKET_CONTEXT_PEPPER` | 32자 이상, 다른 비밀과 분리 |
| `KMARKET_JWT_SECRET_BASE64` | Base64 디코딩 기준 64바이트 이상 |
| `KMARKET_JWT_ISSUER` | 기본값 `k-market-navigator` |
| `KMARKET_JWT_AUDIENCE` | 기본값 `k-market-navigator-api` |

운영 비밀값은 `.env`나 저장소에 커밋하지 않고 배포 플랫폼의 비밀 저장소에서 주입한다.
