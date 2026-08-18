# K-Market-Navigator Backend

외국인 투자자가 한국 상장기업 공시를 탐색하고 공시 근거를 바탕으로 질문할 수 있도록 수집·저장·조회 API를 제공하는 Spring Boot 서비스다.

현재 구현 범위는 실시간 공시 목록, 공시 상세, 공시 전용 RAG 연동으로 제한한다.

## 프로젝트 문서

- [제품 범위](docs/PRODUCT_SCOPE.md)
- [Git 및 전달 워크플로](docs/GIT_WORKFLOW.md)
- [Codex 저장소 지침](AGENTS.md)
- [Codex 작업 스킬](.agents/skills/k-market-delivery/SKILL.md)

## 개발 환경

- Java 25 LTS
- Spring Boot 4.1.0
- PostgreSQL 18

## 실행

```shell
cp .env.example .env
set -a
source .env
set +a
./gradlew bootRun
```

`.env`는 로컬에서만 사용하며 Git에 포함하지 않는다. 배포 환경의 설정은 실행 시점에 외부에서 주입한다.

상태 확인은 `GET /actuator/health`를 사용한다. 그 외 경로는 인증 방식이 확정될 때까지 기본 차단한다.

## 검증

```shell
./gradlew test
```
