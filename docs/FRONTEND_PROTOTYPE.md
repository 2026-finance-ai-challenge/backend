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
| 랜딩·통합검색 | `#/home`, `#/search` | 자동완성·관심종목·종목/기간/공시 유형/감성/중요도 필터와 시장·뉴스·공시 API |
| 스크리너 | `#/screener` | 시장·업종·등락률·거래량·관심종목·거래 유의 필터, `/api/v1/market/stocks`, `/foreign-limits` |
| 종목 상세 | `#/stock/{code}` | 종목 상세·이력·글로벌 피어 |
| 뉴스 | `#/news`, `#/article/{id}` | 영어 제목 목록, 상세 최초 번역·AI Insight 요청, 생성·캐시 상태, 본문 선택 해설·문맥 질문 |
| DART | `#/dart`, `#/filing/{receipt}` | 기간·유형·정정 필터, 색인 상태, 목록·상세·요약·색인·문단 질문 |
| Tax Guide | `#/tax` | 국가·투자자 유형 자격 비교, 필요 서류, 사용자 문서 검증, Tax Agent 문맥 |
| 회원·마이페이지 | `#/auth`, `#/account` | 인증·프로필·관심종목·최근 조회·알림·채팅·문서 |

AI Agent는 모든 화면에서 열 수 있으며 `Ask AI` 액션이 현재 Stock·News·Filing·Tax 문맥의 기존 채팅방을 찾거나 문맥이 고정된 새 채팅방을 만든다. 선택한 뉴스 문장과 공시 문단은 입력창에 전달할 수 있다. 공시 문맥은 접수번호와 문서 버전을 함께 저장한다.

뉴스·공시 목록은 영어 제목을 우선 표시하되 비동기 제목 번역이 아직 준비되지 않았으면 한국어 제목과 `Translation pending` 상태를 함께 표시한다. 뉴스 상세와 공시 섹션 번역은 최초 요청의 `202`·진행 상태를 표시하고 완료 뒤 다시 조회한다. 캐시된 결과는 즉시 표시하며 실패 시 한국어 원문을 유지하고 재시도 액션을 제공한다.

`Holdings` 뉴스 범위는 주문·보유 자산 브로커 연동이 제품 제외 범위이므로 가짜 목록을 만들지 않고 연결되지 않은 상태를 표시한다. 실시간 공급자나 생성형 AI가 비활성화된 경우에도 `Unavailable`, stale, partial failure, insufficient evidence를 서로 구분한다.

## 인증 계약

- Access JWT는 JavaScript 메모리에만 보관하고 영속 브라우저 저장소에 기록하지 않는다.
- Refresh JWT는 Backend의 회전 API를 통해 갱신하며 서버의 Redis 세션 상태가 최종 권한을 결정한다.
- 로그아웃·전체 로그아웃·비밀번호 변경·계정 삭제 뒤 기존 세션은 재사용할 수 없다.
- 401 응답은 로그인 화면으로 이동시키되 기존 화면 경로를 복원할 수 있게 유지한다.

## 별도 프론트 구현 시 유지할 사항

- `frontend/src/api.ts`의 오류 코드·커서·인증 갱신 계약을 우선 재사용한다.
- `source`, `asOf`, `confidence`, `modelVersion`과 stale/unavailable 상태를 숨기지 않는다.
- 공시 인용은 문단·표 식별자를 보존하고 다른 공시 답변과 합치지 않는다.
- 번역 생성 중·실패·원문 미제공 상태를 AI 분석 실패와 구분하며, 번역이 없어도 한글 원문과 공시 RAG 인용을 사용할 수 있어야 한다.
- Nginx의 CSP, nosniff, Referrer-Policy, Permissions-Policy와 비특권 실행 조건을 유지한다.
- 키보드 포커스, 스크린리더 레이블, 색상 외 상태 표현, 모바일 전체 화면 Agent 동작을 유지한다.
