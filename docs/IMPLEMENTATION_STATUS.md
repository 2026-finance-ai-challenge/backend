# 기능정의서 v2.0 구현 현황

기준일: 2026-08-24

`완료`는 API 계약, Backend 연동 프로토타입과 자동 검증이 구현됐다는 뜻이다. 외부 공급자의 실시간 응답까지 항상 존재한다는 뜻은 아니다.

| 기능정의 영역 | 구현 | 외부 조건·명시적 제한 |
| --- | --- | --- |
| 75종목 검색·시세·스크리너 | 완료 | 실시간 시세·거래 상태는 KIS 자격증명 필요 |
| 뉴스 수집·분류·번역·What/Why/Impact·용어 해설 | 완료 | Naver/OpenAI 자격증명 필요. 분류 모델은 검증된 Hana 모델과 K-FNSPID 사용 |
| DART 목록·상세·버전·무결성 | 완료 | 누적 로컬 데이터 사용 가능. 신규 수집은 OpenDART 키 필요 |
| 공시 요약·RAG·인용·근거 부족 거절 | 완료 | 생성 답변은 OpenAI 키와 AI API 필요 |
| 외국인 한도 4종목·예측·거래 유의 | 완료 | 실측 갱신은 KRX/KIS 자격증명 필요 |
| 범용 Agent·채팅방 관리 | 완료 | Stock·News·Filing·Tax 문맥과 서버 근거만 사용 |
| 글로벌 피어 | 완료 | 검증 참고 데이터가 없는 `0126Z0`은 `Unavailable` |
| 세무 자격·보안 업로드·OCR | 완료 | 안내·문서 검증이며 세무 당국 승인 기능이 아님 |
| Redis JWT 회원·개인화 | 완료 | Argon2id, 15분 Access JWT, Refresh 회전·재사용 탐지, Redis 폐기·로그인 제한 |
| 반응형·접근성·공통 상태 | 완료 | Desktop·Tablet·Mobile 레이아웃과 Loading·Empty·Stale·오류 상태 구현 |

## 검증 증거

- Backend: `./gradlew check --no-daemon` 통과
- AI: Ruff, 포맷, mypy, 34개 테스트 통과
- Frontend: Node 테스트 3개와 프로덕션 빌드 통과
- Docker: PostgreSQL, Redis, AI API, Backend, Frontend 헬스체크 통과
- 통합 보안: 실제 회원가입·로그인으로 Access JWT 발급, Redis 세션 응답, CSP 등 보안 헤더 확인
- 모델 런타임: 읽기 전용 Hana 저장소의 허용 commit·SHA-256을 확인한 뒤 실제 분류 추론 성공

외부 공급자의 계약·자격증명이 없으면 해당 수집기는 기본 비활성화된다. 이 경우 기존 저장 데이터와 명시적 `Unavailable`·지연·부분 실패 상태를 사용하며 임의 현재가·뉴스·한도·AI 결과를 만들지 않는다.
