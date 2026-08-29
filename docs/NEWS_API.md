# 뉴스 인텔리전스 API

## 범위

- Naver Search API에서 시장 뉴스와 지원 종목 75개 관련 뉴스를 수집한다.
- 정규화 URL 해시로 동일 URL을 멱등 저장하고, 최근 72시간의 제목·요약을 독립 비교해 언론사별 재전송 기사를 동일 사건 군집으로 묶는다.
- 원문 출처는 근거와 보도 범위 확인을 위해 보존하되 목록에는 군집 대표 기사 한 건만 노출한다. `relatedCoverageCount`는 같은 사건으로 묶인 전체 출처 수다.
- 관심종목 알림도 원문별이 아니라 군집 대표 기사 기준으로 한 번만 생성한다.
- 현재 시각보다 72시간 이상 오래된 검색 결과와 15분 이상 미래 시각인 결과는 저장하지 않는다. 최근 72시간에 수집한 원문 군집은 6시간마다 같은 판정식으로 재검증한다.
- 검색 API가 제공하는 제목과 요약만 저장하며, 제공받지 않은 기사 전문을 보유한 것처럼 표시하지 않는다.
- 목록용 영어 제목은 수집 후 비동기 작업으로 생성하고 정규화 제목 해시 기반 번역 메모리에서 재사용한다.
- 기사 본문 또는 검색 요약의 영어 번역과 What/Why/Impact는 사용자가 상세·AI Insight를 처음 요청할 때만 생성하고 원문 해시 기준으로 저장한다.
- 수집 트랜잭션은 OpenAI 응답을 기다리지 않으며 본문 일괄 선번역을 수행하지 않는다.
- 이벤트·감성·의미 중요도는 하나금융 프로젝트의 승인된 금융 NLP 모델이 계산한다.
- 시장영향 중요도와 점수는 배포 gate를 통과한 K-FNSPID 모델이 계산한다.
- 시장영향 방향(`POSITIVE`, `NEUTRAL`, `NEGATIVE`, `UNCERTAIN`)과 중요도(`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`)는 다른 필드다.
- 모델 저장소의 허용 Git commit 또는 핵심 파일 SHA-256이 다르면 뉴스 분석을 fail-closed 처리하고 OpenAI가 분류값을 대신 생성하지 않는다.

## API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/news` | 종목·기간·감성·의미 중요도·시장영향 방향·시장영향 중요도·관심종목 필터와 커서 페이지 조회 |
| `GET` | `/api/v1/news/{articleId}` | 기사 원문 제공 범위, 제목 번역, 번역·AI Insight 상태, 관련 종목, 중복 기사 수 조회 |
| `GET` | `/api/v1/news/{articleId}/translation` | 저장된 영어 본문·What/Why/Impact 또는 현재 생성 상태 조회 |
| `POST` | `/api/v1/news/{articleId}/translation` | 영어 본문·What/Why/Impact 온디맨드 생성 요청 또는 캐시 재사용 |
| `POST` | `/api/v1/news/{articleId}/term-explanations` | 기사에서 실제 선택한 텍스트의 금융 문맥 해설 |

목록 정렬은 `LATEST`, `IMPORTANCE`, `MARKET_IMPACT`를 지원한다. `MARKET_IMPACT`는 방향이 아니라 K-FNSPID 점수를 기준으로 정렬한다. 검색·종목·관심종목 필터는 대표 기사뿐 아니라 군집에 속한 모든 출처를 대상으로 적용한다. `watchlist=true`는 인증 사용자의 관심종목 연관 기사만 반환하며 서버가 소유권을 검증한다. 다음 페이지는 응답의 `nextCursor`를 그대로 전달한다. 원문 전문이 없으면 `contentAvailability=SOURCE_EXCERPT`이며 `originalBody`는 `null`이다.

주요 응답 필드는 `sentiment`, `importance`, `marketImpact`, `marketImpactImportance`, `marketImpactScore`, 각 confidence, 모델·프롬프트 버전이다. 감성·의미 중요도·시장영향 중요도를 하나의 값으로 합치지 않는다.

번역 캐시 적중 시 `POST`도 `200 READY`를 반환한다. 최초 또는 동시 생성 요청은 `202`와 동일 작업 ID·`Retry-After`를 반환한다. 검색 API가 제공하지 않은 전문은 `409 SOURCE_CONTENT_UNAVAILABLE`, 사용량 제한은 `429`, 공급자 장애·회로 차단은 `503`으로 구분한다.

## 수집·분류·생성 상태

| 상태 | 의미 |
| --- | --- |
| `PENDING` | 분류·제목 번역 또는 사용자가 요청한 본문 생성 작업 대기 |
| `PROCESSING` | 임대 기반으로 워커가 점유한 작업 |
| `READY` | 해당 작업의 구조화 응답 검증과 저장 완료 |
| `FAILED` | 제목·온디맨드 번역은 최대 10회, 분류는 최대 5회 실패해 자동 재시도가 종료됨 |

분류, 제목 번역, 본문·Insight 생성 상태는 서로 독립적으로 관리한다. 분류 또는 제목 번역 실패가 원문 저장을 취소하지 않는다. 작업은 PostgreSQL의 `FOR UPDATE SKIP LOCKED`와 임대 만료 회수를 사용하고, 온디맨드 생성은 Redis 분산 잠금과 PostgreSQL 유일 제약으로 중복을 막는다.

## 금융 용어 해설 보안

- 서버가 현재 기사 안에 실제로 존재하는 선택 텍스트인지 확인하고 주변 문맥만 AI 서비스에 전달한다.
- 기사 문맥과 검증 사전의 근거 ID만 인용할 수 있으며, 반환된 근거 ID를 서버가 다시 허용 목록으로 검증한다.
- 직접 식별자는 전달하지 않고 서버 비밀값으로 해시한 요청 식별자를 사용한다.
- Redis에서 IP 해시별 시간당 20회로 제한한다.
- 실제 해설 버튼 요청이 접수되고 선택 문맥 검증을 통과했을 때만 익명 클릭 통계를 기록한다.

## 환경 변수

| 환경 변수 | 설명 |
| --- | --- |
| `KMARKET_NAVER_NEWS_ENABLED` | Naver 뉴스 수집 활성화, 기본 `false` |
| `KMARKET_NAVER_NEWS_CLIENT_ID` | 서버 비밀 저장소에 넣는 Naver Client ID |
| `KMARKET_NAVER_NEWS_CLIENT_SECRET` | 서버 비밀 저장소에 넣는 Naver Client Secret |
| `KMARKET_NAVER_NEWS_DISPLAY` | 검색어별 요청 건수, 1~100 |
| `KMARKET_NAVER_NEWS_TARGET_BATCH_SIZE` | 수집 주기당 종목 검색 수, 1~75 |
| `KMARKET_NEWS_COLLECTION_INTERVAL` | 뉴스 수집 주기, 기본 10분 |
| `KMARKET_NEWS_MAX_ARTICLE_AGE` | 검색 결과 저장 허용 발행 경과 시간, 기본 72시간 |
| `KMARKET_NEWS_ANALYSIS_INTERVAL` | 분석 큐 확인 주기, 기본 5초 |
| `KMARKET_NEWS_RECONCILIATION_INTERVAL` | 기존 기사 사건 군집 재검증 주기, 기본 6시간 |

OpenAI 모델과 프롬프트 버전은 AI 서비스의 `KMARKET_AI_NEWS_MODEL`, `KMARKET_AI_NEWS_PROMPT_VERSION`, `KMARKET_AI_TERM_PROMPT_VERSION`으로 관리한다. 분류 런타임은 Hana 프로젝트 경로, 허용 commit과 모델 SHA-256으로 고정한다. KF-DeBERTa v6 후보는 공식 gate가 `KEEP_CURRENT_MODEL`이므로 활성 모델로 표기하지 않는다. API 키는 AI 서비스에만 주입하며 브라우저나 Backend 응답에 포함하지 않는다.

뉴스 수집은 원문과 제목 번역 작업만 저장하며 생성 응답을 기다리지 않는다. 분류 워커는 로컬 금융 모델 신호만 저장하고, 제목 워커는 최대 25개를 구조화 번역한다. 본문·What/Why/Impact는 상세 화면의 명시적 요청이 있을 때만 별도 작업으로 생성한다.
