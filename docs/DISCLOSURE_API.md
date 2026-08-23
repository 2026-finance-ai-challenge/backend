# 공시 인텔리전스 API

## 범위

- 지원 종목 75개의 1999년 이후 OpenDART 공시를 조회한다.
- 공시 상세는 현재 원문 문서의 목차·문단·표와 SHA-256을 제공한다.
- 정정공시는 정규화된 공시명과 발행사를 기준으로 이전 버전과 연결한다.
- AI 요약은 현재 문서 버전의 근거만 사용하며 근거가 없으면 생성을 거절한다.

## API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/disclosures` | 종목·기간·공시 유형·정정 여부 필터와 커서 페이지 조회 |
| `GET` | `/api/v1/disclosures/{receiptNumber}` | 메타데이터, 정정 버전, 현재 구조화 원문 조회 |
| `GET` | `/api/v1/disclosures/{receiptNumber}/insight` | 현재 원문 SHA-256 조합에 대응하는 AI 요약 조회 |
| `POST` | `/api/v1/disclosures/{receiptNumber}/insight` | 근거 고정 What/Why/Impact 요약 생성 또는 캐시 재사용 |
| `POST` | `/api/v1/disclosures/{receiptNumber}/questions` | 현재 공시 또는 선택 문단 범위 RAG 질의응답 |
| `POST` | `/api/v1/disclosures/{receiptNumber}/index` | 온디맨드 색인 요청 |

## 정정 버전

상세 응답의 `versions`는 같은 발행사·공시군에 속한 접수번호를 시간순으로 제공한다.
`correctionOfReceiptNumber`는 정정공시가 바로 앞선 버전을 참조하며, 수집 순서가 뒤바뀌어도 서버가 전체 공시군을 다시 연결한다.

## AI 요약 안전성

- 서버가 현재 문서의 ID·버전·SHA-256으로 `contentVersionHash`를 계산한다.
- 입력은 최대 100개 근거와 60,000자로 제한하고 각 문단에 일회성 별칭을 부여한다.
- AI가 반환한 출처는 요청에 포함된 별칭 허용 목록과 대조한 뒤 실제 섹션 UUID로 변환한다.
- 충분한 근거가 있다는 응답에 유효한 출처가 없으면 저장하거나 노출하지 않는다.
- 공시 원문은 신뢰할 수 없는 데이터로 취급하며 내부 명령으로 실행하지 않는다.
- 모델 ID, 프롬프트 버전, 생성 시각을 응답에 포함하고 OpenAI 응답 저장은 비활성화한다.

원문 문서가 준비되지 않았으면 `409 DISCLOSURE_DOCUMENT_NOT_READY`, 현재 버전 요약이 아직 없으면 `404 DISCLOSURE_INSIGHT_NOT_READY`를 반환한다.
