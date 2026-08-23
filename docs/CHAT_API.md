# AI Agent 채팅방 API

## 보안 및 소유권

- 모든 API는 Redis 세션이 활성 상태인 Access JWT를 요구한다.
- 채팅방 조회·변경·삭제 SQL은 `user_id`를 항상 조건에 포함한다.
- 다른 사용자의 채팅방은 존재 여부를 노출하지 않고 `404 CHAT_ROOM_NOT_FOUND`를 반환한다.
- 응답은 `Cache-Control: no-store`로 브라우저 공유 캐시에 남기지 않는다.

## API

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/v1/me/chats` | 서버가 검증한 화면 문맥으로 새 채팅방 생성 |
| `GET` | `/api/v1/me/chats` | 최근 사용 순 목록 및 이름 검색 |
| `GET` | `/api/v1/me/chats/{roomId}` | 채팅방과 고정 문맥 복원 |
| `PUT` | `/api/v1/me/chats/{roomId}/name` | 예상 버전 기반 이름 변경 |
| `DELETE` | `/api/v1/me/chats/{roomId}` | 사용자 화면에서 즉시 제거 |
| `POST` | `/api/v1/me/chats/{roomId}/messages` | 사용자 메시지와 비동기 생성 작업을 멱등 생성 |
| `GET` | `/api/v1/me/chats/{roomId}/messages` | 순서가 보존된 메시지와 검증된 인용 조회 |
| `GET` | `/api/v1/me/chats/{roomId}/generations/{generationId}` | 생성·재시도·실패 상태 조회 |
| `POST` | `/api/v1/me/chats/{roomId}/generations/{generationId}/stop` | 대기 또는 처리 중 생성 중단 |
| `POST` | `/api/v1/me/chats/{roomId}/generations/{generationId}/retry` | 최종 실패 작업 재시도 |
| `POST` | `/api/v1/me/chats/{roomId}/messages/{assistantMessageId}/regenerate` | 동일 사용자 질문으로 새 답변 생성 |

`contextType`은 `GENERAL`, `STOCK`, `NEWS`, `FILING`, `TAX_GUIDE` 중 하나다. 클라이언트가 제공한 표시명이나 문서 버전은 신뢰하지 않는다. 종목·뉴스·공시는 서버 데이터에서 실제 표시명과 참조를 다시 조회하며, 공시는 현재 구조화 원문의 SHA-256 조합에 바인딩한다.

이름 변경은 현재 `version`을 `expectedVersion`으로 보내야 한다. 동시에 다른 요청이 수정했으면 `409 CHAT_ROOM_VERSION_CONFLICT`로 최신 상태 재조회를 요구한다.

삭제된 채팅방은 즉시 모든 사용자 API에서 숨기고 30일 복구 정책 기간이 지난 후 분산 잠금이 적용된 정리 작업이 영구 파기한다.

## 답변 생성

- 메시지 제출은 `202 Accepted`와 `generation.id`를 반환하며 클라이언트는 생성 상태를 조회한다.
- `clientMessageId`와 재생성 `requestKey`는 네트워크 재전송에도 중복 메시지·과금을 만들지 않는 멱등 키다.
- 생성 작업은 PostgreSQL `SKIP LOCKED` 큐로 한 번에 하나의 worker만 점유하며, 중단된 worker의 잠금은 5분 뒤 복구한다.
- 일시 장애는 지수 백오프로 최대 3회 재시도한 후 `FAILED`로 전환한다. 사용자는 오류 코드를 확인하고 명시적으로 다시 시도할 수 있다.
- 사용자별 생성 요청은 Redis의 고정 시간창으로 제한한다.
- OpenAI에는 직접 사용자 ID 대신 서버 비밀 pepper로 만든 HMAC-SHA-256 식별자만 전달하고 응답 저장은 비활성화한다.
- 종목·뉴스·시장 답변은 서버가 만든 근거 묶음만 사용한다. 반환 인용 ID는 허용 목록과 대조하며 검증되지 않은 인용이 있는 답변은 근거 부족 상태로 바꾼다.
- 공시 답변은 채팅방 생성 당시 원문 해시와 현재 원문 해시가 일치할 때만 공시별 RAG를 실행한다. 선택 문단도 해당 원문의 실제 섹션과 문자열 포함 여부를 확인한다.
