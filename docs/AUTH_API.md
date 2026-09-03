# Redis JWT 회원 인증 API

## 보안 모델

- 신규·변경 비밀번호는 8~128자이며 영문·숫자·특수기호를 각각 포함한다. 대문자는 필수가 아니며 공백·제어문자를 허용하지 않는다. 기존 비밀번호 로그인은 영향을 받지 않는다.
- 최소 8자는 서비스 선택 정책이며 MFA 없는 서비스의 강한 비밀번호 권고와 동일하다고 표시하지 않는다. 로그인 제한·Argon2id·세션 폐기는 유지한다.
- 비밀번호는 OWASP 권고 파라미터의 Argon2id로 해시하며 원문을 저장하거나 로그에 남기지 않는다.
- Access Token은 HS512 서명의 15분 JWT다. `iss`, `aud`, `sub`, `sid`, `jti`, `iat`, `exp`를 검증한다.
- JWT에 로그인 ID, 국적 등 사용자 프로필을 넣지 않는다.
- 모든 인증 요청은 JWT 검증 후 Redis의 활성 세션 상태를 추가 확인한다. 로그아웃·비밀번호 변경·계정 삭제 시 기존 JWT가 즉시 무효화된다.
- Refresh Token은 384비트 난수이며 Redis에는 SHA-256 해시만 저장한다.
- Refresh Token은 사용할 때마다 회전한다. 이미 회전된 토큰이 다시 제출되면 같은 토큰 계열을 모두 폐기한다.
- Refresh 회전 전 발급된 Access JWT도 원래 만료까지 유효하다. 다른 탭의 갱신으로 진행 중인 요청을 무효화하지 않는다. 로그아웃은 해당 로그인 계열 전체, 전체 로그아웃·비밀번호 변경·탈퇴는 사용자 전체 세션을 즉시 폐기한다.
- 로그인 실패 횟수는 로그인 ID와 클라이언트 IP의 비가역 해시를 기준으로 Redis에서 15분 동안 관리한다. 5회 실패 후 `429`와 `Retry-After`를 반환한다.
- IP와 User-Agent는 서버 비밀 pepper를 적용한 SHA-256 해시만 감사 로그에 저장한다.

## API

| Method | Path | 인증 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/auth/login-id-availability?loginId=` | 공개 | 로그인 ID 중복 확인 |
| `POST` | `/api/v1/auth/signup` | 공개 | 계정 생성 |
| `POST` | `/api/v1/auth/login` | 공개 | Access JWT와 Refresh Token 발급 |
| `POST` | `/api/v1/auth/refresh` | 공개 | Refresh Token 회전과 새 토큰 발급 |
| `POST` | `/api/v1/auth/logout` | Bearer JWT | 현재 로그인 계열 폐기 |
| `POST` | `/api/v1/auth/browser/login` | 신뢰 Origin·CSRF 헤더 | Access JWT와 HttpOnly refresh 쿠키 발급 |
| `POST` | `/api/v1/auth/browser/refresh` | refresh 쿠키·Origin·CSRF 헤더 | 세션 복원 및 쿠키 회전 |
| `POST` | `/api/v1/auth/browser/logout` | refresh 쿠키·Origin·CSRF 헤더 | 로그인 계열 폐기 및 쿠키 삭제 |
| `POST` | `/api/v1/auth/logout-all` | Bearer JWT | 사용자의 전체 세션 폐기 |
| `GET` | `/api/v1/me` | Bearer JWT | 프로필 조회 |
| `PUT` | `/api/v1/me/password` | Bearer JWT | 비밀번호 변경 후 전체 세션 폐기 |
| `DELETE` | `/api/v1/me` | Bearer JWT | 비밀번호 확인 후 계정 소프트 삭제 |

로그인과 Refresh 응답은 `Cache-Control: no-store`를 포함한다. 보호 API는 다음 헤더를 사용한다.

가입의 `fscDisclaimerAccepted=true`는 계정 생성과 같은 트랜잭션에서 `FSC_DISCLAIMER_ACCEPTED` 감사 이벤트로 기록한다. 문서 버전은 `fsc-disclaimer-v1`이다. 이전 클라이언트의 필드 생략은 허용하되 동의로 기록하지 않으며 명시적인 `false`는 거부한다.

```http
Authorization: Bearer <access-jwt>
```

네이티브 `/auth/refresh` 요청 본문은 다음 형식이다.

```json
{
  "refreshToken": "kmr_<opaque-token>"
}
```

## 브라우저 세션

- 브라우저는 `/auth/browser/*`를 사용한다. refresh 토큰은 JSON 응답에 포함하지 않는다.
- `kart_browser_refresh`는 호스트 전용 `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth/browser` 쿠키다. Access JWT는 메모리에만 보관한다.
- 브라우저 요청은 `credentials: include`, `X-KART-CSRF: 1`을 사용한다. 서버는 설정된 정확한 Origin과 전용 헤더를 함께 확인한다. credential CORS는 브라우저 인증 경로에만 허용한다.
- 새로고침 시 보호 화면을 표시하기 전에 한 번 세션을 복원한다. 탭 안에서는 single-flight, 탭 간에는 Web Locks로 쿠키 회전을 직렬화한다. 401만 세션 만료로 처리하고 통신·서버 장애에서는 복원 재시도를 제공한다.
- `/browser/refresh` 본문에는 UUID `requestId`를 전달한다. 브라우저는 응답을 받을 때까지 이 비밀이 아닌 요청 식별자만 보존한다. 같은 이전 토큰·요청 ID·클라이언트 문맥은 120초 이내에 이미 발급된 결과를 복구한다. 후속 세션이 활성 상태여야 하며 다른 ID·문맥·시간 초과는 기존 재사용 탐지대로 계열을 폐기한다. 후속 토큰은 서버 비밀로 파생하고 Redis에는 해시만 저장한다.
- 운영 UI와 API는 HTTPS의 같은 사이트(`www.kartkr.cloud`, `api.kartkr.cloud`)를 사용한다. 다른 사이트의 임의 미리보기 도메인은 인증 지원 대상이 아니다.
- 로컬 프론트는 `127.0.0.1:5173`에만 바인딩된 개발 프록시를 통한다. 이 HTTP 루프백 응답에서만 해당 쿠키의 Secure 속성을 제거한다. 운영 서버의 쿠키 보안 설정은 바꾸지 않는다.
- 네이티브 API 계약은 유지하며 네이티브 클라이언트는 운영체제 보안 저장소를 사용한다.

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
