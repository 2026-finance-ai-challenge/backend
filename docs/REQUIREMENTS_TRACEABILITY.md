# 기능정의서·와이어프레임 v2.0 추적표

기준일: 2026-08-25

이 문서는 요구사항이 실제 화면, API, 서버 정책과 검증 항목 중 어디에 반영됐는지 연결한다. 실시간 외부 데이터는 자격증명과 이용 권한이 있을 때만 활성화하며, 미연결 상태는 구현 누락이 아니라 명시적 `Unavailable` 상태로 다룬다.

## 핵심 기능

| 요구 영역 | 화면·사용자 흐름 | 서버·AI 근거 | 검증·운영 상태 |
| --- | --- | --- | --- |
| 75종목 검색·자동완성 | Hero/GNB 자동완성, 통합 검색, 관심종목 하트 | Market API, 75종목 Flyway 카탈로그, 사용자 Watchlist 소유권 검증 | 코드·이름·영문명·별칭 검색 및 미지원 종목 상태 구현 |
| 뉴스 인텔리전스 | 영어 제목, 홈·목록 호버, 상세 최초 번역·AI Insight, 본문 선택 해설 | 제목 번역 메모리, EN 본문, EN/KR What/Why/Impact 온디맨드 캐시, Hana·K-FNSPID 분류 | 언어별 동시 요청 1건, 최초 `202`·완료 `200`, 새로고침 없는 화면 갱신 |
| DART Intelligence | 영어 제목 목록, 목차·표·문단, 섹션 온디맨드 번역, 버전·원문, AI 요약·질문 | ZIP·SHA-256 카탈로그, 제목 번역 메모리, 구조화 payload, 공시별 HALFVEC 한글 검색·영어 답변 | RAG 격리·인용·거절, 제목 백필, 현재 버전 섹션 공유 캐시와 표 구조 검증 완료 |
| 글로벌 피어 | 종목 상세의 비교 탭과 분석 액션 | 검증 참고 카탈로그, OpenAI 구조화 설명, PostgreSQL 캐시·Redis 잠금 | 참고 데이터 없는 `0126Z0`은 임의 피어 대신 `Unavailable` |
| 실시간 스크리너 | 시장/업종/등락률/거래량/관심/주의 필터, 종목 상세 배지 | KIS 시세·상태, KRX 외국인 보유, 신선도·회로 차단 | 미연결·지연 값을 0으로 치환하지 않음 |
| 실시간 지수·종목 차트 | 홈 KOSPI·KOSDAQ, 종목 현재가, 기간별 OHLCV·거래량·툴팁 | KIS `H0UPCNT0`·`H0STCNT0`, SSE, 40개 LRU, REST 분봉 백필 | 1D 10분봉·1W 1시간봉·1M/3M/1Y 일봉, 장외 마지막 종가 유지 |
| 외국인 한도 | 4종목 게이지, 임계치 경고, Min/Base/Max 밴드 | 003490·015760·017670·032640 정책, 일별 예측 엔진 | 기본 90% 임계값, source/asOf/confidence/modelVersion 노출 |
| 범용 AI Agent | 전 화면 패널, 문맥 배지, 방 생성/검색/전환/이름/삭제, 메시지 액션 | 서버 Evidence Provider, 비동기 생성 작업, 채팅 보존·소유권·요청 제한 | Stock/News/Filing/Tax 문맥 고정, 삭제 방 Not Found, 최신 사실 임의 생성 금지 |
| 세무 가이드 | 국가·투자자 유형 비교, 문서 카드, 업로드·검증 상태, 제출 절차 | 조세조약 데이터, MIME/magic byte 검사, AES-GCM 저장, OpenAI OCR·일관성 검증 | Verified/Review Required/Rejected 구분, 세무 승인으로 표현하지 않음 |
| 계정·개인화 | 가입/로그인, 마이페이지, Watchlist, 최근 조회, 알림, My Chats | Argon2id, Access JWT, Redis Refresh 세션·회전·폐기, 로그인 제한, 소유권 검증 | 실제 가입·로그인·JWT·Redis 통합 스모크 통과 |

## 공통 화면·비기능

| 요구 영역 | 구현 위치 | 보장 |
| --- | --- | --- |
| GNB·반응형 | React 프로토타입 공통 Shell·CSS | Desktop/Tablet/Mobile 내비게이션, 모바일 전체 화면 Agent |
| 공통 상태 | `RemoteBoundary`와 각 도메인 상태 카드 | Loading, Empty, Partial Failure, Stale, Unauthorized, Forbidden, Rate Limited, AI Failure, Insufficient Evidence, Unsupported Stock 구분 |
| 접근성 | 공통 컴포넌트·폼·모달 | 키보드 포커스, 레이블, 색상 외 텍스트·아이콘 상태 표현 |
| API 키 보호 | Backend/AI 환경 설정과 Nginx 역방향 프록시 | 브라우저 번들·응답에 외부 API 키나 AI 서비스 토큰 미포함 |
| 프롬프트 보안 | Backend 최소화·AI 신뢰 경계·스키마 검증 | 외부 원문과 사용자 입력을 내부 명령으로 취급하지 않음 |
| 감사·추적 | 요청 ID, 공시 버전, source/asOf, 모델·프롬프트 버전 | 결과의 출처·기준 시각·생성 버전 추적 가능 |
| 생성 비용 통제 | 제목 비동기 선번역, 본문 최초 요청 생성, 이후 즉시 재사용 | 원문 SHA-256·언어·정책 버전 캐시, Redis 잠금, PostgreSQL 유일 제약, 일일 비용 상한 |
| 파일 보안 | 서버 업로드 검증·무작위 파일명·계정 경로·AES-GCM | 허용 형식과 크기만 저장하고 계정 소유권·감사 로그 적용 |

## 의도적으로 연결 상태로 남긴 항목

- `Holdings` 피드는 실제 주문·보유자산 브로커 연동이 제외 범위이므로 연결되지 않은 상태를 표시한다.
- KIS·KRX·Naver·OpenDART·OpenAI의 라이브 호출은 계약 자격증명이 있을 때만 켠다.
- KF-DeBERTa v6 감성 후보는 공식 평가가 `KEEP_CURRENT_MODEL`이므로 승인된 현행 Hana 모델을 유지한다.
- 프로토타입은 백엔드 계약과 전체 UX 상태를 검증하기 위한 참고 구현이며 별도 프론트 팀이 디자인 시스템을 교체할 수 있다.

## 완료 검증 명령

```shell
./gradlew check --no-daemon
npm --prefix frontend test
npm --prefix frontend run build
./scripts/verify-local.sh
```

`verify-local.sh`는 Docker 이미지 빌드, 5개 서비스 헬스체크, 모델 무결성·실추론, 회원가입·로그인, JWT 발급, Redis 응답과 프론트 보안 헤더를 한 번에 검증한다.
