# 세무 자격·문서 검증 API

세무 기능은 정보 안내와 제출 전 문서 점검만 제공한다. 응답은 세무 당국의 승인이나 세무·법률 확정 판단이 아니며, 최종 적용 세율과 제출 가능 여부는 브로커 또는 세무 전문가에게 확인해야 한다.

## 조세조약 안내

아래 API는 인증 없이 조회할 수 있다.

- `GET /api/v1/tax/countries`: 운영 데이터에 등록된 국가와 기준일 조회
- `POST /api/v1/tax/eligibility`: 거주 국가와 투자자 유형에 따른 국내 기본 원천징수율, 조세조약 일반 제한세율, 조건부 법인 세율 조회

요청 예시:

```json
{
  "residencyCountry": "US",
  "investorType": "INDIVIDUAL"
}
```

세율은 `src/main/resources/tax/treaty-dividend-rates.json`의 기준일과 국세청 원문 링크를 함께 반환한다. 지원되지 않는 국가는 세율을 추정하지 않고 `treatyDataAvailable=false`로 응답한다. 조건부 법인 세율은 지분율 등 조약 요건을 충족한다는 의미가 아니며 참고값으로만 제공한다.

## 문서 검증

모든 문서 API에는 `Authorization: Bearer <access-token>`이 필요하며 JWT 소유 사용자의 문서만 조회·변경할 수 있다.

- `POST /api/v1/me/tax-documents`: 문서 업로드 및 비동기 검증 요청
- `GET /api/v1/me/tax-documents`: 내 문서 목록
- `POST /api/v1/me/tax-documents/comparison`: 소유권이 확인된 문서 3종 교차검증
- `GET /api/v1/me/tax-documents/{documentId}`: 진행률·추출 필드·검증 결과
- `POST /api/v1/me/tax-documents/{documentId}/retry`: 실패 문서 재시도
- `DELETE /api/v1/me/tax-documents/{documentId}`: 화면에서 즉시 제거하고 보존기간 후 암호문 파기

업로드는 `multipart/form-data`이며 다음 필드를 사용한다.

- `documentType`: `RESIDENCY_CERTIFICATE`, `APOSTILLE`, `REDUCED_TAX_APPLICATION`
- `expectedResidencyCountry`: ISO 3166-1 alpha-2 국가 코드
- `file`: PDF, JPEG 또는 PNG, 최대 10 MiB

같은 사용자가 같은 유형과 SHA-256의 문서를 중복 업로드하면 기존 활성 문서를 반환한다. 암호화된 PDF, JavaScript·실행 액션이 포함된 PDF, 확장자·MIME·magic byte가 다른 파일은 거부한다.

교차검증 요청은 각 유형의 문서 ID를 정확히 하나씩 전달한다. Backend는 JWT 소유권과 문서 상태를 확인한 뒤 저장된 개별 OCR 판정과 정규화 필드만 내부 AI 서비스에 전달한다. AI 서비스는 원본 검토기의 성명·TIN·거주국 일치 규칙을 실행한다. 비교 시 원문을 복호화·재전송·재OCR하지 않으므로 중복 연산과 장시간 동기 요청을 만들지 않는다.

상태는 다음과 같다.

| 상태 | 의미 |
| --- | --- |
| `PROCESSING` | 암호화 저장 후 OCR 또는 검증 대기·처리 중 |
| `VERIFIED` | 필수 필드와 문서 간 일관성 규칙을 통과 |
| `REVIEW_REQUIRED` | 누락, 불일치, 낮은 신뢰도 또는 위·변조 위험 신호가 있어 사람 검토 필요 |
| `REJECTED` | 문서 유형·국가·핵심 필드가 요청과 맞지 않음 |
| `FAILED` | 제한된 재시도 후 외부 AI 또는 처리 실패 |

## 보안과 보존

- 원본은 사용자·문서별 파생 AES-256-GCM 키와 고유 nonce로 암호화하며 AAD로 소유권과 문서 ID를 결합한다.
- 저장 경로는 무작위 UUID만 사용하고 심볼릭 링크를 거부한다.
- 내부 AI 서비스에는 직접 사용자 식별자 대신 비가역 해시 식별자를 보낸다. 세무 OCR은 OpenAI를 호출하지 않는다.
- OCR 결과는 문서 내용을 신뢰할 수 없는 입력으로 취급하고 정부 발급 진위나 승인 여부를 단정하지 않는다.
- 업로드는 Redis에서 사용자별 시간당 10회로 제한한다.
- 삭제한 문서는 기본 30일 뒤 암호문과 추출 개인정보를 파기하고 최소 감사 tombstone만 남긴다.
- 암호화 키는 최소 32바이트 무작위 값을 Base64로 주입하고 저장소나 이미지에 포함하지 않는다.

키 생성 예시:

```shell
openssl rand -base64 32
```
