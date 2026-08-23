# 뉴스 인텔리전스 API

## 범위

- Naver Search API에서 시장 뉴스와 지원 종목 75개 관련 뉴스를 수집한다.
- 정규화 URL 해시로 동일 기사를 멱등 저장하고, 최근 72시간의 제목·요약 유사도로 동일 이슈를 묶는다.
- 검색 API가 제공하는 제목과 요약만 저장하며, 제공받지 않은 기사 전문을 보유한 것처럼 표시하지 않는다.
- OpenAI 구조화 출력으로 영문 번역, 이벤트, 감성, 중요도, 시장영향, What/Why/Impact를 생성한다.
- 감성·중요도·시장영향은 서로 독립된 필드이며 각각 신뢰도를 제공한다.

## API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/news` | 종목·기간·감성·중요도·시장영향 필터와 커서 페이지 조회 |
| `GET` | `/api/v1/news/{articleId}` | 기사 원문 제공 범위, 번역·AI 분석, 관련 종목, 중복 기사 수 조회 |
| `POST` | `/api/v1/news/{articleId}/term-explanations` | 기사에서 실제 선택한 텍스트의 금융 문맥 해설 |

목록 정렬은 `LATEST`, `IMPORTANCE`, `MARKET_IMPACT`를 지원한다. 다음 페이지는 응답의 `nextCursor`를 그대로 전달한다. 원문 전문이 없으면 `contentAvailability=SOURCE_EXCERPT`이며 `originalBody`는 `null`이다.

## 수집·분석 상태

| 상태 | 의미 |
| --- | --- |
| `PENDING` | OpenAI 분석 대기 또는 제한된 재시도 대기 |
| `PROCESSING` | 다른 워커가 점유한 분석 작업 |
| `READY` | 구조화 응답 검증과 저장 완료 |
| `FAILED` | 최대 5회 실패해 자동 재시도가 종료됨 |

분석 작업은 PostgreSQL의 `FOR UPDATE SKIP LOCKED`와 ShedLock을 함께 사용한다. 중단된 `PROCESSING` 작업은 10분 후 손실 없이 회수하며 지수 백오프로 최대 5회 처리한다.

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
| `KMARKET_NEWS_ANALYSIS_INTERVAL` | 분석 큐 확인 주기, 기본 5초 |

OpenAI 모델과 프롬프트 버전은 AI 서비스의 `KMARKET_AI_NEWS_MODEL`, `KMARKET_AI_NEWS_PROMPT_VERSION`, `KMARKET_AI_TERM_PROMPT_VERSION`으로 관리한다. API 키는 AI 서비스에만 주입하며 브라우저나 Backend 응답에 포함하지 않는다.
