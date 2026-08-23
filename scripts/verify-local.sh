#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
project_name=${COMPOSE_PROJECT_NAME:-kmarket-harness}

if [ "$project_name" = "backend" ] || [ -z "$project_name" ]; then
  echo "검증용 Compose 프로젝트 이름이 안전하지 않습니다." >&2
  exit 1
fi

cleanup() {
  COMPOSE_PROJECT_NAME="$project_name" compose down --volumes --remove-orphans
}
trap cleanup EXIT INT TERM

compose() {
  if [ -n "${KMARKET_DOCKER_CONFIG_DIR:-}" ]; then
    docker-compose "$@"
  else
    docker compose "$@"
  fi
}

cd "$repository_dir"
./gradlew check --no-daemon
npm --prefix frontend ci --ignore-scripts --no-audit --no-fund
npm --prefix frontend test
npm --prefix frontend run build

if [ -n "${KMARKET_DOCKER_CONFIG_DIR:-}" ]; then
  mkdir -p "$KMARKET_DOCKER_CONFIG_DIR"
  export DOCKER_CONFIG="$KMARKET_DOCKER_CONFIG_DIR"
fi
export SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME:-kmarket_test}
export SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD:-local-test-password}
export KMARKET_AI_SERVICE_TOKEN=${KMARKET_AI_SERVICE_TOKEN:-local-test-service-token-32-chars}
export REDIS_PASSWORD=${REDIS_PASSWORD:-local-test-redis-password}
export KMARKET_CONTEXT_PEPPER=${KMARKET_CONTEXT_PEPPER:-local-test-context-pepper-32-bytes}
export KMARKET_JWT_SECRET_BASE64=${KMARKET_JWT_SECRET_BASE64:-bG9jYWwtdGVzdC1qd3Qtc2VjcmV0LTAxMjM0NTY3ODktQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVotYWJjZGVmZ2g=}
export KMARKET_TAX_DOCUMENT_KEY_BASE64=${KMARKET_TAX_DOCUMENT_KEY_BASE64:-MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}
export OPENDART_API_KEYS=${OPENDART_API_KEYS:-0000000000000000000000000000000000000000}
export POSTGRES_PORT=${POSTGRES_PORT:-55432}
export REDIS_PORT=${REDIS_PORT:-56379}
export AI_PORT=${AI_PORT:-18000}
export BACKEND_PORT=${BACKEND_PORT:-18080}
export FRONTEND_PORT=${FRONTEND_PORT:-15101}

COMPOSE_PROJECT_NAME="$project_name" compose config --quiet
COMPOSE_PROJECT_NAME="$project_name" compose build backend ai-api frontend
COMPOSE_PROJECT_NAME="$project_name" compose up --detach --wait postgres redis ai-api backend frontend

curl --fail --silent --show-error "http://127.0.0.1:${AI_PORT}/health" >/dev/null
curl --fail --silent --show-error "http://127.0.0.1:${BACKEND_PORT}/actuator/health" >/dev/null
curl --fail --silent --show-error "http://127.0.0.1:${FRONTEND_PORT}/healthz" >/dev/null
curl --fail --silent --show-error "http://127.0.0.1:${FRONTEND_PORT}/api/v1/market/indices" >/dev/null
if ! curl --fail --silent --show-error --head "http://127.0.0.1:${FRONTEND_PORT}/" | grep -qi "content-security-policy"; then
  echo "프론트엔드 보안 헤더 검증에 실패했습니다." >&2
  exit 1
fi
if [ "$(docker exec "${project_name}-redis-1" redis-cli --no-auth-warning -a "$REDIS_PASSWORD" ping)" != "PONG" ]; then
  echo "Redis 검증에 실패했습니다." >&2
  exit 1
fi
echo "로컬 통합 검증이 완료되었습니다."
