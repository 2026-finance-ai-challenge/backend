# 뉴스 인텔리전스 API

## 범위

- Naver Search API에서 지원 종목 75개를 10분마다 모두 조회한다. 종목명·종목코드가 제목 또는 요약에 실제 등장한 기사만 저장하며 검색어 적중 자체는 관련성 근거로 사용하지 않는다.
- 영문 단축 종목명은 유니코드 단어 경계로 판정하고, `SK하이닉스` 안의 `SK`처럼 긴 회사명과 겹치는 짧은 회사명은 제외한다.
- 정규화 URL 해시로 동일 URL을 멱등 처리하고 최근 1시간의 제목·본문을 비교한다. 같은 사건의 다른 언론사 기사는 저장 전에 폐기하므로 제목 번역과 분석 작업도 생성하지 않는다.
- 목록과 종목 필터는 저장된 단일 대표 기사와 그 기사에서 검증된 종목 연결만 사용한다. `relatedCoverageCount`는 하위 호환을 위해 유지하며 신규 데이터에서는 `1`이다.
- 관심종목 알림도 검증된 대표 기사 기준으로 한 번만 생성한다.
- 현재 시각보다 1시간 이상 오래된 검색 결과와 15분 이상 미래 시각인 결과는 저장하지 않는다.
- `news-relevance-dedup-v3` 1회성 정리 작업은 기존 기사를 동일 규칙으로 다시 판정해 최신 관련 기사만 남기고, 중복·무관 기사와 연결이 끊긴 제목 번역 작업을 삭제한다.
- `SK`, `LG`, `HLB`, `HMM`처럼 짧고 다른 의미로 쓰일 수 있는 이름은 기사 제목에 명시된 경우만 연결한다. 긴 고유 회사명은 제목과 요약 모두에서 검증한다.
- Naver 검색 결과의 제목·요약은 원문 후보 선별에만 사용하고 DB에는 저장하지 않는다. 언론사 공개 페이지에서 추출·정제한 원문 전문, 정규 URL, 대표 이미지를 함께 확보하지 못한 기사는 폐기한다.
- 원문 요청은 HTTP(S) 80/443만 허용하고 DNS 결과의 로컬·사설·링크 로컬 주소를 차단한다. 리디렉션마다 동일 검증을 다시 수행하며 응답 크기·리디렉션 횟수·본문 길이를 제한한다.
- 목록용 영어 제목은 수집 후 비동기 작업으로 생성하고 정규화 제목 해시 기반 번역 메모리에서 재사용한다.
- 최근 1시간 내 원문·분류·영문 제목이 준비된 대표 기사는 영어 본문과 What/Why/Impact를 같은 작업에서 자동 생성하고 원문 해시 기준으로 저장한다.
- 수집 트랜잭션은 OpenAI 응답을 기다리지 않으며 최근 1시간 범위의 별도 작업 큐가 번역과 요약을 처리한다.
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

목록 정렬은 `LATEST`, `IMPORTANCE`, `MARKET_IMPACT`를 지원한다. `MARKET_IMPACT`는 방향이 아니라 K-FNSPID 점수를 기준으로 정렬한다. 검색·종목·관심종목 필터는 대표 기사 자체와 검증된 종목 연결을 대상으로 적용한다. `watchlist=true`는 인증 사용자의 관심종목 연관 기사만 반환하며 서버가 소유권을 검증한다. 다음 페이지는 응답의 `nextCursor`를 그대로 전달한다. 공개 API는 `FULL_ARTICLE`, 제목 번역 `READY`, 분류 `READY`, 영어 본문과 What/Why/Impact가 모두 충족된 기사만 반환한다.

주요 응답 필드는 `sentiment`, `importance`, `marketImpact`, `marketImpactImportance`, `marketImpactScore`, 각 confidence, 모델·프롬프트 버전이다. 감성·의미 중요도·시장영향 중요도를 하나의 값으로 합치지 않는다.

번역 캐시 적중 시 `POST`도 `200 READY`를 반환한다. 최초 또는 동시 생성 요청은 `202`와 동일 작업 ID·`Retry-After`를 반환한다. 원문 전문이 없는 내부 데이터는 공개하지 않으며, 직접 요청 시 `409 SOURCE_CONTENT_UNAVAILABLE`, 사용량 제한은 `429`, 공급자 장애·회로 차단은 `503`으로 구분한다.

## 수집·분류·생성 상태

| 상태 | 의미 |
| --- | --- |
| `PENDING` | 분류·제목 번역 또는 사용자가 요청한 본문 생성 작업 대기 |
| `PROCESSING` | 임대 기반으로 워커가 점유한 작업 |
| `READY` | 해당 작업의 구조화 응답 검증과 저장 완료 |
| `FAILED` | 제목·온디맨드 번역은 최대 10회, 분류는 최대 5회 실패해 자동 재시도가 종료됨 |

분류, 제목 번역, 본문·Insight 생성 상태는 서로 독립적으로 관리한다. 분류 또는 제목 번역 실패가 원문 저장을 취소하지 않는다. 작업은 PostgreSQL의 `FOR UPDATE SKIP LOCKED`와 임대 만료 회수를 사용하고, 온디맨드 생성은 Redis 분산 잠금과 PostgreSQL 유일 제약으로 중복을 막는다.

제목 번역 공급자 오류는 타임아웃·속도 제한·크레딧 소진·일반 장애로 구분한다. 일시적 타임아웃은 30초부터 지수 백오프로 재시도하고, 속도 제한은 1분, 크레딧 소진만 15분 전역 쿨다운을 적용해 실시간 처리를 복구하면서 불필요한 유료 호출 반복을 막는다.

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
| `KMARKET_NAVER_NEWS_TARGET_BATCH_SIZE` | 수집 주기당 종목 검색 수, 기본 `75` |
| `KMARKET_NAVER_NEWS_QUERIES` | 추가 시장 검색어, 기본값 없음. 결과도 지원 종목이 명시된 경우만 저장 |
| `KMARKET_NEWS_MAINTENANCE_ENABLED` | 기존 뉴스 1회성 정리 활성화, 운영 기본 `true` |
| `KMARKET_NEWS_COLLECTION_INTERVAL` | 뉴스 수집 주기, 기본 10분 |
| `KMARKET_NEWS_MAX_ARTICLE_AGE` | 검색 결과 저장 허용 발행 경과 시간, 기본 1시간 |
| `KMARKET_NEWS_ANALYSIS_INTERVAL` | 분석 큐 확인 주기, 기본 5초 |
| `KMARKET_NEWS_MAINTENANCE_INTERVAL` | 배포 후 기존 뉴스 정리 완료 여부 확인 주기, 기본 1시간 |

OpenAI 모델과 프롬프트 버전은 AI 서비스의 `KMARKET_AI_NEWS_MODEL`, `KMARKET_AI_NEWS_PROMPT_VERSION`, `KMARKET_AI_TERM_PROMPT_VERSION`으로 관리한다. 분류 런타임은 Hana 프로젝트 경로, 허용 commit과 모델 SHA-256으로 고정한다. KF-DeBERTa v6 후보는 공식 gate가 `KEEP_CURRENT_MODEL`이므로 활성 모델로 표기하지 않는다. API 키는 AI 서비스에만 주입하며 브라우저나 Backend 응답에 포함하지 않는다.

뉴스 수집은 원문과 제목 번역 작업만 저장하며 생성 응답을 기다리지 않는다. 분류 워커는 로컬 금융 모델 신호만 저장하고, 제목 워커는 최대 25개를 구조화 번역한다. 최근 1시간 내 완결된 대표 기사의 본문·What/Why/Impact는 별도 작업에서 자동 생성하며 상세 요청은 같은 캐시를 멱등 재사용한다.
