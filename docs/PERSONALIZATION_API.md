# 사용자 개인화 API

## 보안과 소유권

- 모든 API는 Redis 세션이 활성 상태인 Access JWT를 요구한다.
- 사용자 ID를 URL이나 요청 본문에서 받지 않고, 서명과 Redis 상태가 검증된 JWT의 `sub`만 사용한다.
- 관심종목과 최근 조회, 알림 조회·수정 SQL은 항상 인증 사용자 ID를 조건으로 포함한다.
- 알림 읽기는 타인 알림의 존재 여부를 노출하지 않도록 `404 NOTIFICATION_NOT_FOUND`를 반환한다.
- 사용자별 응답은 `Cache-Control: no-store`로 중간 캐시 저장을 금지한다.

## API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/me/watchlist` | 관심종목 목록 |
| `PUT` | `/api/v1/me/watchlist/{stockCode}` | 지원 종목 관심목록 추가, 멱등 |
| `DELETE` | `/api/v1/me/watchlist/{stockCode}` | 관심종목 해제, 멱등 |
| `POST` | `/api/v1/me/recently-viewed` | 종목·뉴스·공시 최근 조회 기록 |
| `GET` | `/api/v1/me/recently-viewed?limit=20` | 최근 조회 목록, 최대 100건 |
| `GET` | `/api/v1/me/notifications?cursor=&limit=20` | 알림함 커서 페이지, 안 읽은 건수 포함 |
| `PUT` | `/api/v1/me/notifications/{notificationId}/read` | 소유 알림 읽음 처리 |
| `PUT` | `/api/v1/me/notifications/read-all` | 모든 소유 알림 읽음 처리 |

## 관심종목

관심종목은 `service_stock_universe`에 활성화된 75개 지원 종목만 등록할 수 있다. 같은 종목의 중복 추가는 하나의 항목으로 처리한다.

## 최근 조회

```json
{
  "itemType": "FILING",
  "referenceId": "20260818800679",
  "stockCode": "005930"
}
```

- `STOCK`은 `referenceId`와 `stockCode`가 같아야 한다.
- `FILING`은 DB에 있는 지원 종목 공시만 기록한다.
- 같은 대상을 다시 기록하면 조회 시각만 갱신한다.
- 사용자별 최근 100건만 유지한다.

## 알림 페이지

`nextCursor`는 서버가 생성한 불투명 문자열이다. 클라이언트는 해석하지 않고 다음 요청의 `cursor`로 그대로 전달한다. 유효하지 않은 커서는 `400 INVALID_CURSOR`를 반환한다.
