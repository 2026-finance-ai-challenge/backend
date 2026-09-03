# 운영 배포

`main` 푸시 시 Backend ARM64 이미지만 GHCR에 발행하고 Backend 서비스만 `--no-deps`로 교체한다. AI·RAG 작업자·DB·Redis를 함께 재기동하지 않는다. Frontend는 별도 저장소와 호스팅에서 배포한다.

- `runtime.env`는 서버에만 보관하고 권한을 `0600`으로 제한한다.
- 데이터베이스·Redis·AI 포트는 공개하지 않는다. Backend는 호스트 루프백에만 바인딩한다.
- 호스트 Nginx는 `/api`와 Swagger 경로만 Backend로 전달하고 그 외 경로는 `404`로 차단한다.
- Nginx는 `api.kartkr.cloud`의 Let's Encrypt 인증서만 사용하며 HTTP 요청을 같은 도메인의 HTTPS로 전환한다.
- 운영 Frontend와 로컬 검증 Origin만 CORS 허용 목록에 등록한다.
- 배포 스크립트는 동시 실행을 잠그고 헬스체크가 완료된 뒤 성공한다.
- CI는 비밀을 `runtime.incoming.env`로 전달하며 배포 잠금 안에서 병합한다. 누락·빈 비밀이 기존 비밀이나 복구·수집 설정을 지우지 않는다. 의도적 설정 삭제는 운영자가 별도로 적용한다.
- 이전 이미지 태그가 아니라 실제 실행 이미지 ID와 환경 파일을 보관하고 배포 실패 시 Backend만 복구한다. 종료 유예는 240초이며 DB 작업 상태·임대와 데이터 볼륨을 초기화하지 않는다.
- HTTPS Nginx 설정은 헬스체크 후 호스트에 설치하고 `nginx -t`를 통과한 경우에만 무중단 재로드한다. 검증이 실패하면 이전 설정으로 복구한다.

## 이미지 보존

`retain-images.py`는 기본적으로 삭제 후보만 출력한다. `--apply`에서만 강제 옵션 없이 삭제한다. KART의 Backend·AI 저장소별 최신 5개, 실행·중지 컨테이너가 참조하는 이미지, `backend-last-good.image`·`ai-last-good.image`, `image-retention-pins.txt`에 명시한 복구 이미지를 보존한다. 보호 이미지가 확인되지 않으면 삭제를 중단한다. 다른 저장소·태그 없는 이미지·볼륨·모델 캐시·원문 백업은 건드리지 않는다.

배포 후와 `kart-image-retention.timer`의 일일 실행에 같은 잠금을 사용한다. 복구가 끝나면 수동 보호 목록 중 더 이상 필요 없는 항목만 검토해 해제한다. GHCR 이미지는 커밋 태그로 다시 받을 수 있으나 로컬 전용 이미지는 삭제 후 자동 복원되지 않는다.
