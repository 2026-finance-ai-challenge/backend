# OpenAPI·Swagger UI

Backend의 실행 가능한 API 계약은 Spring MVC 컨트롤러와 DTO에서 OpenAPI 3.0 문서로 자동 생성한다. 수동 문서는 기능 배경과 운영 정책을 설명하고, 요청·응답 스키마와 현재 경로는 OpenAPI 문서를 기준으로 연동한다. Springdoc 3.1.0의 OpenAPI 3.1 스키마 복제 회귀가 해결된 정식 버전이 나오기 전까지 Swagger UI와 클라이언트 생성기 호환성이 안정적인 3.0 계약을 사용한다.

## 운영 URL

- Swagger UI: `https://api.kartkr.cloud/swagger-ui/index.html`
- Swagger UI 진입점: `https://api.kartkr.cloud/swagger-ui.html`
- OpenAPI JSON: `https://api.kartkr.cloud/v3/api-docs`
- OpenAPI YAML: `https://api.kartkr.cloud/v3/api-docs.yaml`

로컬 Frontend는 `http://localhost:5173`에서 운영 Backend를 호출하고, 운영 Frontend는 `https://kartkr.cloud`에서 호출한다. 두 Origin만 운영 CORS 허용 목록에 명시하며, 운영에서는 Frontend 컨테이너를 실행하지 않는다. Swagger의 Server URL은 `/` 상대 경로이므로 현재 접속한 환경으로 요청하며 내부 컨테이너 주소를 노출하지 않는다.

## 인증

`Authentication`의 로그인 API에서 발급받은 Access Token을 Swagger UI의 `Authorize`에 입력한다. `Bearer ` 접두어는 UI가 자동으로 추가한다.

- 자물쇠 없음: 인증 불필요
- 닫힌 자물쇠: Access JWT 필수
- 선택 인증: 비회원 호출이 가능하고, 토큰이 있으면 관심종목 등 사용자 문맥을 함께 반영

Swagger UI는 브라우저 저장소에 인증 값을 보존하지 않는다. 새로고침하거나 창을 닫으면 다시 입력해야 한다.

뉴스·공시 번역 요청은 캐시된 결과가 있으면 `200 OK`, 비동기 처리가 필요하면 `202 Accepted`를 반환하며 두 응답을 모두 OpenAPI에 명시한다.

## 운영 전송

운영 HTTPS 경계 Nginx는 `api.kartkr.cloud` 인증서를 사용하고 Swagger JavaScript·CSS와 OpenAPI JSON·YAML을 gzip으로 압축한다. Backend 응답의 중복 HSTS·`X-Content-Type-Options`·`X-Frame-Options` 헤더를 제거하고 하나의 정책 값으로 재설정한다. Frontend는 별도 저장소와 배포 환경에서 운영하며 Backend Compose 이미지로 빌드하거나 실행하지 않는다.

## 문서 그룹

- `Authentication`: 회원가입, 로그인, 토큰 회전, 계정 관리
- `Personalization`: 관심종목, 최근 조회, 알림
- `AI Chat`: 근거 기반 Agent 채팅방과 메시지
- `Market`: 종목 검색, 스크리너, 시세, 지수, 환율, 글로벌 피어
- `News`: 검증된 종목 뉴스, 수집 전 중복 차단, 번역, 금융용어 해설
- `Disclosures`: 공시, 번역, 인사이트, RAG 질의
- `Tax`: 조세조약 안내와 암호화 세무 문서 검증

오류 응답은 `application/problem+json` 형식이며 실제 API 권한은 Swagger 표시와 관계없이 Spring Security에서 검증한다.

## 최신 상태 검증

자동 테스트는 다음 조건을 검사한다.

1. 실행 중인 모든 `/api/v1/**` Spring MVC 매핑과 OpenAPI `paths`가 정확히 일치한다.
2. 모든 HTTP 작업에 고유한 `operationId`, 한글 요약과 기능 태그가 존재한다.
3. JWT 필수 API와 공개 API의 보안 정의가 구분된다.
4. Swagger UI와 JSON·YAML 문서가 인증 없이 제공된다.
5. 생성되는 계약 버전이 경고 없는 OpenAPI 3.0 형식이다.

API를 추가하거나 경로를 변경할 때 `OpenApiConfig`의 요약·태그·인증 수준을 함께 갱신하지 않으면 검증이 실패한다.
