# 공시 인텔리전스 API

## 범위

- 지원 종목 75개의 1999년 이후 OpenDART 공시를 조회한다.
- 공시 상세는 현재 원문 문서의 목차·문단·표와 SHA-256을 제공한다.
- 정정공시는 정규화된 공시명과 발행사를 기준으로 이전 버전과 연결한다.
- AI 요약은 현재 문서 버전의 근거만 사용하며 근거가 없으면 생성을 거절한다.
- 목록용 영어 제목은 정규화된 제목 번역 메모리에서 재사용하고 수집 직후 비동기 작업으로 채운다.
- 본문은 전체 선번역하지 않고 사용자가 요청한 현재 문서 버전의 섹션·표·문단만 영어로 번역해 저장한다.

## API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/disclosures` | 종목·기간·공시 유형·정정 여부 필터와 커서 페이지 조회 |
| `GET` | `/api/v1/disclosures/{receiptNumber}` | 메타데이터, 정정 버전, 현재 구조화 원문 조회 |
| `GET` | `/api/v1/disclosures/{receiptNumber}/insight` | 현재 원문 SHA-256 조합에 대응하는 AI 요약 조회 |
| `POST` | `/api/v1/disclosures/{receiptNumber}/insight` | 근거 고정 What/Why/Impact 요약 생성 또는 캐시 재사용 |
| `POST` | `/api/v1/disclosures/{receiptNumber}/questions` | 현재 공시 또는 선택 문단 범위 RAG 질의응답 |
| `POST` | `/api/v1/disclosures/{receiptNumber}/index` | 온디맨드 색인 요청 |
| `GET` | `/api/v1/disclosures/{receiptNumber}/sections/{sectionId}/translation` | 현재 문서 버전의 저장된 영어 섹션 번역 또는 생성 상태 조회 |
| `POST` | `/api/v1/disclosures/{receiptNumber}/sections/{sectionId}/translation` | 영어 섹션 번역 온디맨드 생성 요청 또는 캐시 재사용 |

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

## 다국어 검색과 번역 분리

- 한글 원문 청크와 영어 질문을 고정된 다국어 임베딩 모델의 같은 벡터 공간에서 비교한다.
- 검색 범위는 접수번호와 현재 문서 버전으로 고정하며 번역된 본문을 RAG 인덱스로 사용하지 않는다.
- OpenAI에는 영어 질문과 검색된 한글 근거만 전달하고, 답변은 영어로 생성한다.
- 번역 캐시가 없거나 실패해도 RAG 질문은 처리할 수 있다. 영어 인용문이 필요하면 답변과 별개로 해당 섹션 번역을 요청한다.
- 섹션 번역은 `문서 버전 + sectionId + 원문 SHA-256 + targetLocale + 번역 정책 버전`으로 구분한다.

번역 캐시가 있으면 `POST`도 `200 READY`, 최초 또는 동시 요청은 `202`와 동일 작업 ID·`Retry-After`를 반환한다. 원문 문서·섹션이 없으면 `409`, 사용량 제한은 `429`, 공급자 장애·회로 차단은 `503`으로 구분한다.

현재 공시 RAG는 한글 원문 다국어 검색과 영어 답변을 사용한다. 제목 번역 메모리와 기존 234,071건 백필은 완료됐으며 목록·상세·영문 검색에서 `titleEn`을 재사용한다. 섹션 번역 API는 현재 상세 응답에 속한 섹션만 허용하고, 같은 원문 해시의 `READY` 결과를 모든 사용자에게 재사용한다.
