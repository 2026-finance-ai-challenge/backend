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

`contextType`은 `GENERAL`, `STOCK`, `NEWS`, `FILING`, `TAX_GUIDE` 중 하나다. 클라이언트가 제공한 표시명이나 문서 버전은 신뢰하지 않는다. 종목·뉴스·공시는 서버 데이터에서 실제 표시명과 참조를 다시 조회하며, 공시는 현재 구조화 원문의 SHA-256 조합에 바인딩한다.

이름 변경은 현재 `version`을 `expectedVersion`으로 보내야 한다. 동시에 다른 요청이 수정했으면 `409 CHAT_ROOM_VERSION_CONFLICT`로 최신 상태 재조회를 요구한다.

삭제된 채팅방은 즉시 모든 사용자 API에서 숨기고 30일 복구 정책 기간이 지난 후 분산 잠금이 적용된 정리 작업이 영구 파기한다.
