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

세무 채팅은 사용자당 활성 방 하나에 저장한다. `POST /api/v1/me/tax-conversation`은 기존 방과 저장된 언어·세율 결과·비교 결과를 반환하고, 없는 경우에만 생성한다. `/eligibility`는 세율 결과를 저장한다. `/restart`는 소유한 `roomId`와 `locale`을 받아 기존 방·문서·암호문을 삭제한 뒤 새 방을 만든다. 업로드·판정·세율 조회·비교 완료 시 최근 사용 시각을 갱신한다.

모든 문서 API에는 `Authorization: Bearer <access-token>`이 필요하며 JWT 소유 사용자의 문서만 조회·변경할 수 있다.

- `POST /api/v1/me/tax-documents`: 문서 업로드 및 비동기 검증 요청
- `GET /api/v1/me/tax-documents`: 내 문서 목록
- `POST /api/v1/me/tax-documents/comparison`: 소유권이 확인된 문서 3종 교차검증
- `GET /api/v1/me/tax-documents/{documentId}`: 진행률·추출 필드·검증 결과
- `POST /api/v1/me/tax-documents/{documentId}/retry`: 원본이 남은 일시 장애 건만 재시도. 파기된 파일은 새 업로드가 필요하다.
- `GET /api/v1/me/tax-documents/{documentId}/original`: 소유권 확인 후 원본 제공. `contentAvailable=false`인 문서는 원본이 파기된 상태다.
- `DELETE /api/v1/me/tax-documents/{documentId}`: 목록에서 제거하고 암호문·추출 필드를 즉시 파기

업로드는 `multipart/form-data`이며 다음 필드를 사용한다.

- `documentType`: `RESIDENCY_CERTIFICATE`, `APOSTILLE`, `REDUCED_TAX_APPLICATION`
- `expectedResidencyCountry`: ISO 3166-1 alpha-2 국가 코드
- `file`: PDF, JPEG 또는 PNG, 최대 10 MiB

같은 사용자의 처리 중·통과 문서는 유형과 SHA-256으로 중복 저장을 방지한다. 실패 파일을 다시 선택하면 새로운 업로드로 처리하며, 기존 파일을 재검증하지 않는다. 이전 단계가 `VERIFIED`여야 다음 종류를 받는다. 암호화된 PDF, JavaScript·실행 액션이 포함된 PDF, 확장자·MIME·magic byte가 다른 파일은 거부한다.

개별 문서 검증은 해당 문서의 유형·필수 필드·국가·유효기간·위변조 신호만 판정한다. 다른 문서와의 일치 여부는 업로드 순서에 따라 결과가 달라지지 않도록 개별 판정에 섞지 않는다.

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
- 삭제한 문서 및 미통과 문서는 암호문과 추출 개인정보를 즉시 파기한다. 미통과 판정 기록은 대화 복원을 위해 남기며 정리 실패·과거 잔존 파일은 정기 파기 작업에서 재처리한다. 세무 채팅 재시작·삭제는 문서 행과 기존 방까지 삭제한다.
- 암호화 키는 최소 32바이트 무작위 값을 Base64로 주입하고 저장소나 이미지에 포함하지 않는다.

키 생성 예시:

```shell
openssl rand -base64 32
```
# 사전 점검 상태와 완료 문서

- `/api/v1/me`의 `taxVerificationStatus`는 현재 문서와 대화 상태에서 계산한다. `NOT_STARTED`는 시작 전, `IN_PROGRESS`는 진행 중(검증 실패·재업로드 포함), `VERIFIED`는 3종 문서와 비교 검증 완료다. 계정 생성 시 저장된 과거 상태를 기준으로 사용하지 않는다.
- 초기화는 기존 문서와 방을 제거하고 새 방을 만들므로 `IN_PROGRESS`가 된다. 완료 문서 접근 권한도 즉시 사라진다.
- `GET /api/v1/me/tax-review-package`는 본인의 검증 완료 문서 3종을 반환한다.
- `GET /api/v1/me/tax-review-package?locale=en|ko`는 원본 목록과 읽기 전용 폼 좌표·추출값을 제공한다. `fieldsRefreshing`이 true면 기존 원본의 누락 필드를 보완 중이며 완료될 때까지 재조회한다.
- `GET /api/v1/me/tax-review-package/correction.pdf?locale=en|ko`는 하나금융 원본 양식에 성명·납세자번호·거주국·생년월일·전화번호·주소를 합성한 정적 PDF다. 추가 안내 문구와 국가명은 선택 언어를 따르며 법정 원본 양식은 그대로 유지한다. 접수번호·신청일·서명·금액·금융사 정보는 추정하지 않는다.
- 보조 필드 버전 0·1은 수정된 OCR 버전 2로 복구한다. 기존 검증·비교 결과는 유지하며 원본과 신원 일치가 확인된 보조 필드만 갱신한다.
- 과거 신청서의 누락 보조 정보는 원본을 다시 읽어 자동 보완한다. 기존 성명·납세자번호·거주국이 일치할 때만 보조 필드를 저장하고 기존 판정과 비교 결과는 변경하지 않는다. 분산 잠금으로 한 건씩 실행하며 실패 시 5분 간격 최대 3회로 제한한다. 원본이나 기존 검증 정보를 별도 복사하지 않는다.
- PDF는 요청 시 생성하고 영구 저장하지 않는다. 모든 응답은 본인 인증과 완료 상태를 검사하며 `no-store, private`을 사용한다. 국세청 제출·금융사 승인 API는 제공하지 않는다.
