# 프론트 프로토타입 인계 가이드

## 목적

`frontend`는 최종 브랜드 산출물이 아니라 기능정의서 v2.0과 Backend 계약을 실제로 실행·검증하는 참고 구현이다. 가짜 시세나 고정 AI 응답을 넣지 않으며 외부 데이터가 없으면 빈 상태·지연·unavailable 상태를 표시한다.

## 실행

```shell
cp .env.example .env
docker compose up --build -d
```

화면은 `http://127.0.0.1:15101`에서 열 수 있다. Nginx가 `/api`를 Backend로 프록시하므로 브라우저에 외부 API 키나 AI 서비스 토큰이 전달되지 않는다.

개별 개발은 다음 명령을 사용한다.

```shell
npm --prefix frontend ci
npm --prefix frontend run dev
```

## 화면과 API 연결

| 화면 | 해시 경로 | 주요 API |
| --- | --- | --- |
| 랜딩·통합검색 | `#/home`, `#/search` | `/api/v1/market/*`, `/api/v1/news`, `/api/v1/disclosures` |
| 스크리너 | `#/screener` | `/api/v1/market/stocks`, `/foreign-limits` |
| 종목 상세 | `#/stock/{code}` | 종목 상세·이력·글로벌 피어 |
| 뉴스 | `#/news`, `#/article/{id}` | 뉴스 목록·상세·용어 해설 |
| DART | `#/dart`, `#/filing/{receipt}` | 공시 목록·상세·요약·색인·질문 |
| Tax Guide | `#/tax` | 국가·자격 비교·사용자 문서 검증 |
| 회원·마이페이지 | `#/auth`, `#/account` | 인증·프로필·관심종목·최근 조회·알림·채팅·문서 |

AI Agent는 모든 화면에서 열 수 있으며 현재 Stock·News·Filing·Tax 문맥을 새 채팅방에 연결한다. 공시 문맥은 접수번호와 문서 버전을 함께 저장한다.

## 인증 계약

- Access JWT는 JavaScript 메모리에만 보관하고 영속 브라우저 저장소에 기록하지 않는다.
- Refresh JWT는 Backend의 회전 API를 통해 갱신하며 서버의 Redis 세션 상태가 최종 권한을 결정한다.
- 로그아웃·전체 로그아웃·비밀번호 변경·계정 삭제 뒤 기존 세션은 재사용할 수 없다.
- 401 응답은 로그인 화면으로 이동시키되 기존 화면 경로를 복원할 수 있게 유지한다.

## 별도 프론트 구현 시 유지할 사항

- `frontend/src/api.ts`의 오류 코드·커서·인증 갱신 계약을 우선 재사용한다.
- `source`, `asOf`, `confidence`, `modelVersion`과 stale/unavailable 상태를 숨기지 않는다.
- 공시 인용은 문단·표 식별자를 보존하고 다른 공시 답변과 합치지 않는다.
- Nginx의 CSP, nosniff, Referrer-Policy, Permissions-Policy와 비특권 실행 조건을 유지한다.
- 키보드 포커스, 스크린리더 레이블, 색상 외 상태 표현, 모바일 전체 화면 Agent 동작을 유지한다.
