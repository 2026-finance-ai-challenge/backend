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

if [ -n "${KMARKET_DOCKER_CONFIG_DIR:-}" ]; then
  mkdir -p "$KMARKET_DOCKER_CONFIG_DIR"
  export DOCKER_CONFIG="$KMARKET_DOCKER_CONFIG_DIR"
fi
export SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME:-kmarket_test}
export SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD:-local-test-password}
export KMARKET_AI_SERVICE_TOKEN=${KMARKET_AI_SERVICE_TOKEN:-local-test-service-token-32-chars}
export POSTGRES_PORT=${POSTGRES_PORT:-55432}
export AI_PORT=${AI_PORT:-18000}
export BACKEND_PORT=${BACKEND_PORT:-18080}

COMPOSE_PROJECT_NAME="$project_name" compose config --quiet
COMPOSE_PROJECT_NAME="$project_name" compose build backend ai-api
COMPOSE_PROJECT_NAME="$project_name" compose up --detach --wait postgres ai-api backend

curl --fail --silent --show-error "http://127.0.0.1:${AI_PORT}/health" >/dev/null
curl --fail --silent --show-error "http://127.0.0.1:${BACKEND_PORT}/actuator/health" >/dev/null
echo "로컬 통합 검증이 완료되었습니다."
