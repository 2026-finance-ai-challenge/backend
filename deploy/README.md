# 운영 배포

`main` 푸시 시 Backend ARM64 이미지만 GHCR에 발행하고 `/opt/kmarket`의 Compose 스택을 갱신한다. Frontend는 별도 저장소와 호스팅 환경에서 배포하므로 Backend 이미지 발행·실행 대상이 아니다.

- `runtime.env`는 서버에만 보관하고 권한을 `0600`으로 제한한다.
- 데이터베이스·Redis·AI 포트는 공개하지 않는다. Backend는 호스트 루프백에만 바인딩한다.
- 호스트 Nginx는 `/api`와 Swagger 경로만 Backend로 전달하고 그 외 경로는 `404`로 차단한다.
- Nginx는 `api.kartkr.cloud`의 Let's Encrypt 인증서만 사용하며 HTTP 요청을 같은 도메인의 HTTPS로 전환한다.
- 운영 Frontend와 로컬 검증 Origin만 CORS 허용 목록에 등록한다.
- 배포 스크립트는 동시 실행을 잠그고 헬스체크가 완료된 뒤 성공한다.
- HTTPS Nginx 설정은 헬스체크 후 호스트에 설치하고 `nginx -t`를 통과한 경우에만 무중단 재로드한다. 검증이 실패하면 이전 설정으로 복구한다.
