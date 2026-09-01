# 시장·스크리너 API

## 범위

- `service_stock_universe`에 등록된 KOSPI·KOSDAQ 보통주 75개만 검색·조회한다.
- 종목코드는 숫자 또는 영문이 포함된 6자리를 지원한다.
- 외국인 취득 한도 집중 모니터링은 대한항공, 한국전력공사, SK텔레콤, LG유플러스 4개 종목만 적용한다.
- 시세·환율·외국인 보유·예측값이 없으면 0으로 대체하지 않고 `UNAVAILABLE`을 반환한다.

## API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/market/stocks/search?query=` | 종목명·영문명·코드·별칭 관련도 검색 |
| `GET` | `/api/v1/market/stocks` | 시장·업종·등락률·거래 유의·관심종목 스크리너 |
| `GET` | `/api/v1/market/stocks/{stockCode}` | KRW·USD 시세, 외국인 보유, 한도, 거래 상태 |
| `GET` | `/api/v1/market/indices` | KOSPI·KOSDAQ·KOSPI 200 스냅샷 |
| `GET` | `/api/v1/market/exchange-rates/USD` | 원/달러 환율 스냅샷과 데이터 상태 |
| `GET` | `/api/v1/market/foreign-limits` | 4개 종목 한도 게이지와 Min/Base/Max 예측 |
| `GET` | `/api/v1/market/foreign-net-flow` | KOSPI·KOSDAQ 합산 외국인 순매수와 동일 방향 연속 일수 |
| `GET` | `/api/v1/market/stocks/{stockCode}/history` | 일별 OHLCV 차트 데이터 |
| `GET` | `/api/v1/market/stocks/{stockCode}/global-peers` | 데이터 랭커와 OpenAI 구조화 설명 기반 글로벌 피어 분석 |

글로벌 피어 분석은 하나금융 프로젝트에서 검증한 산업·사업모델·규모·재무 하이브리드 랭킹 아티팩트를 사용한다. 서버가 고정한 상위 3개 피어와 4개 강점 키를 OpenAI가 변경할 수 없도록 계약을 검증하며, OpenAI는 영문 설명만 생성한다. 생성 결과는 데이터 버전별로 저장해 중복 비용을 막고 Redis 분산 잠금과 IP 해시별 요청 제한을 적용한다. 피어 자료가 없는 종목은 임의 결과 대신 `GLOBAL_PEER_DATA_UNAVAILABLE`을 반환한다.

관심종목 필터는 JWT 인증이 필요하며, 인증 없이 `watchlist=true`를 요청하면 `401 AUTHENTICATION_REQUIRED`를 반환한다.

## 시세 상태

| 상태 | 의미 |
| --- | --- |
| `LIVE` | 실시간 소스가 명시적으로 제공한 신선한 값 |
| `DELAYED` | REST 스냅샷 등 지연 가능성이 있는 값 |
| `CLOSED` | 장 마감 또는 비거래 시간의 값 |
| `STALE` | 실시간·지연으로 표시됐지만 기준 시각이 2분을 넘긴 값 |
| `UNAVAILABLE` | 제공자 응답 또는 저장된 스냅샷이 없는 상태 |

KIS REST 현재가는 실시간 WebSocket과 구분해 `DELAYED`로 표시한다. VI·단일가 등 실시간 상태를 확인할 수 없는 응답은 `tradingStatusAvailable=false`로 내려가며 정상으로 추정하지 않는다. 외국인 한도 예측의 Min/Base/Max 단위는 한도 소진율이 아니라 외국인 보유율 `%`이며 정규장에만 노출한다. 장외에는 최신 실제 보유율을 사용하고, 법정 한도 대상이 아닌 종목에는 예측을 제공하지 않는다.

## KIS 수집

서버는 [한국투자증권 공식 Open API 예제](https://github.com/koreainvestment/open-trading-api/tree/main/examples_llm/domestic_stock)의 현재가·일별 시세·시장별 투자자 매매동향 계약을 사용한다. Access Token은 Redis에 유효기간보다 60초 짧게 저장하고 분산 잠금으로 중복 발급을 억제한다. 5회 연속 실패 시 1분 회로 차단을 적용한다. 일별 OHLCV는 시작 후와 평일 장 마감 후 갱신하고, KOSPI·KOSDAQ 외국인 순매수는 10분마다 멱등 저장한다.

원/달러 환율은 Frankfurter v2의 `USD/KRW` 공식 일별 기준값을 10분마다 확인해 저장한다. 제공일 기준 종가 데이터이므로 `CLOSED` 상태로 반환한다.

| 환경 변수 | 설명 |
| --- | --- |
| `KMARKET_KIS_ENABLED` | 수집기 활성화, 기본 `false` |
| `KMARKET_KIS_APP_KEY` | 서버에만 저장하는 KIS App Key |
| `KMARKET_KIS_APP_SECRET` | 서버에만 저장하는 KIS App Secret |
| `KMARKET_MARKET_COLLECTION_INTERVAL` | 전체 지원 종목 수집 주기, 기본 60초 |

## 외국인 보유 수집·예측

지원 종목 75개의 실제 외국인 보유율을 평일 장 마감 뒤 멱등 누적한다. KRX 수집기는 최근 45일 이력을 보강하고, KIS 경로는 최신 실제 거래일에 보유수량을 저장한다. 법정 한도 4개 종목만 예측을 생성하고, 나머지는 보유율만 제공하며 Legal limit을 `Not applicable`로 표시한다.

수집 직후와 평일 18:40에 AI 서비스의 승격된 종목별 모델로 Min/Base/Max를 재계산해 DB에 저장한다. 정규장 조회는 최신 수집일과 일치하는 저장 예측을 우선 사용하며, 장외에는 예측값을 노출하지 않고 최신 실제 보유율과 법정 한도만 반환한다. 데이터가 없는 필드는 0으로 대체하지 않는다.

| 환경 변수 | 설명 |
| --- | --- |
| `KMARKET_KRX_ENABLED` | KRX 수집기 활성화, 기본 `false` |
| `KMARKET_KRX_MEMBER_ID` | 서버 비밀 저장소에 넣는 KRX 회원 ID |
| `KMARKET_KRX_PASSWORD` | 서버 비밀 저장소에 넣는 KRX 회원 비밀번호 |
| `KMARKET_KRX_COLLECTION_CRON` | 일별 수집 시각, 기본 평일 18:30 |
| `KMARKET_FOREIGN_PREDICTION_CRON` | 수집 후 예측 재계산 시각, 기본 평일 18:40 |
