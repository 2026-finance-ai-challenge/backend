# 로컬 API 검증 프로토타입

Backend 저장소의 `frontend/`는 `scripts/verify-local.sh`가 사용하는 격리 검증 클라이언트다. 운영 KART 화면이 아니며 운영 Compose에는 포함하지 않는다. 이 디렉터리는 통합 하네스가 참조하므로 유지한다.

서비스 화면의 디자인·API 연결은 별도 Frontend 저장소의 `docs/API_INTEGRATION.md`를 따른다. 인증은 [AUTH_API.md](AUTH_API.md), 공시·번역·RAG는 각 API 문서가 기준이다. 프로토타입의 해시 경로·본문 전달형 인증·과거 표시 방식을 서비스 구현 지침으로 사용하지 않는다.

검증은 `./scripts/verify-local.sh`로 실행한다. 운영과 분리된 Compose 프로젝트·검증 전용 볼륨만 사용한다. 운영 데이터와 동일한 프로젝트 이름이나 볼륨으로 실행하지 않는다.
