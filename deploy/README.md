# 운영 배포

`main` 푸시 시 ARM64 이미지를 GHCR에 발행하고 `/opt/kmarket`의 Compose 스택을 갱신한다.

- `runtime.env`는 서버에만 보관하고 권한을 `0600`으로 제한한다.
- 데이터베이스·Redis·AI·Backend 포트는 공개하지 않는다.
- 프론트엔드는 호스트 Nginx를 통해서만 공개하고 `/api`를 Backend로 전달한다.
- 배포 스크립트는 동시 실행을 잠그고 헬스체크가 완료된 뒤 성공한다.
