# 기능정의서 v2.0 구현 현황

문서 범위: Backend API와 저장소 내 과거 검증용 프로토타입. 별도 KART Frontend의 모든 기능이 연결됐다는 의미가 아니다.

현재 운영·검증 차이는 [파이프라인 점검 기록](PIPELINE_REINFORCEMENT_20260902.md), 인증은 [인증 계약](AUTH_API.md), 서비스 화면별 연결과 미연결 기능은 별도 Frontend 저장소의 `docs/API_INTEGRATION.md`를 따른다. 아래 과거 검증 결과를 최신 실측 수치로 인용하지 않는다.

| 기능정의 영역 | 구현 | 외부 조건·명시적 제한 |
| --- | --- | --- |
| 75종목 검색·시세·스크리너 | 완료 | 실시간 시세·거래 상태는 KIS 자격증명 필요 |
| KOSPI·KOSDAQ·종목 실시간과 OHLCV 차트 | 완료 | KIS WebSocket 지수·종목 체결, 종목 LRU 40개, 1D 10분봉·1W 1시간봉·장기 일봉 |
| 뉴스 수집·중복·분류·용어 해설 | 완료 | 동일 URL 멱등 저장, 제목·요약 독립 유사도 사건 군집, 대표기사 목록, 6시간 재군집. 분류 모델은 검증된 Hana 모델과 K-FNSPID 사용 |
| 뉴스 제목 선번역·본문/Insight 온디맨드 공유 캐시 | 완료 | 제목은 중복 해시별 비동기 배치, EN/KR What/Why/Impact는 최초 호버·상세 요청 후 언어별 공유 |
| DART 목록·상세·버전·무결성 | 완료 | 누적 로컬 데이터 사용 가능. 신규 수집은 OpenDART 키 필요 |
| 공시 요약·RAG·인용·근거 부족 거절 | 완료 | 생성 답변은 OpenAI 키와 AI API 필요 |
| 공시 제목 번역 메모리·백필 | 구현 | 검증된 영문 제목만 노출하며 제목 번역 캐시·작업 큐로 처리. 현재 잔량은 운영 DB에서 확인 |
| 공시 섹션 온디맨드 공유 캐시 | 완료 | 현재 접수번호·문서 버전·섹션·원문 해시에 고정하고 표 구조를 보존해 요청 시 생성·공유 |
| 외국인 한도 4종목·예측·거래 유의 | 완료 | 실측 갱신은 KRX/KIS 자격증명 필요 |
| 범용 Agent·채팅방 관리 | 완료 | Stock·News·Filing·Tax 문맥과 서버 근거만 사용 |
| 글로벌 피어 | 완료 | 검증 참고 데이터가 없는 `0126Z0`은 `Unavailable` |
| 세무 자격·보안 업로드·OCR | 완료 | 안내·문서 검증이며 세무 당국 승인 기능이 아님 |
| Redis JWT 회원·개인화 | 완료 | Argon2id, 15분 Access JWT, Refresh 회전·재사용 탐지, Redis 폐기·로그인 제한 |
| 반응형·접근성·공통 상태 | 완료 | Desktop·Tablet·Mobile 레이아웃과 Loading·Empty·Stale·오류 상태 구현 |
| OpenAPI·Swagger UI | 완료 | 전체 `/api/v1/**` 매핑·스키마·JWT 보안 수준을 런타임에서 생성하고 운영 HTTPS로 제공 |

## 검증 증거

- Backend: `./gradlew test` 통과
- 제목·HTML·RAG 백필은 서로 다른 작업이다. 완료 여부는 각 운영 큐와 저장 상태를 따로 확인한다.
- AI: `uv run pytest -q`, 69개 테스트 통과
- Frontend: Node 테스트 3개와 프로덕션 빌드 통과
- Docker: PostgreSQL, Redis, AI API, Backend, Frontend 헬스체크 통과
- 통합 보안: 실제 회원가입·로그인으로 Access JWT 발급, Redis 세션 응답, CSP 등 보안 헤더 확인
- API 문서: Spring MVC 전체 매핑과 OpenAPI 경로 일치, 모든 작업의 요약·태그·보안 정의와 Swagger UI 응답 검증
- 모델 런타임: 읽기 전용 Hana 저장소의 허용 commit·SHA-256을 확인한 뒤 실제 분류 추론 성공

외부 공급자의 계약·자격증명이 없으면 해당 수집기는 기본 비활성화된다. 이 경우 기존 저장 데이터와 명시적 `Unavailable`·지연·부분 실패 상태를 사용하며 임의 현재가·뉴스·한도·AI 결과를 만들지 않는다.

번역 생성은 Redis `sourceHash:locale` 잠금, PostgreSQL 유일 제약, 임대 만료 회수, 요청 상한을 적용한다. 검증 실패는 유료 재시도 없이 `FAILED`로 종료하며 한국어 원문을 항상 보존한다.
