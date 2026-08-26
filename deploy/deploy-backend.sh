#!/usr/bin/env bash
set -Eeuo pipefail

readonly DEPLOY_ROOT=/opt/kmarket
readonly COMPOSE_FILE="$DEPLOY_ROOT/compose.prod.yaml"
readonly RUNTIME_ENV="$DEPLOY_ROOT/runtime.env"
readonly IMAGE_ENV="$DEPLOY_ROOT/image.env"

if [[ $# -ne 1 || ! "$1" =~ ^[0-9a-f]{40}$ ]]; then
  echo "40자 Git 커밋 SHA가 필요합니다." >&2
  exit 2
fi
: "${GHCR_USERNAME:?required}"
: "${GHCR_TOKEN:?required}"

exec 9>"$DEPLOY_ROOT/.deploy.lock"
flock 9
umask 077

printf '%s' "$GHCR_TOKEN" | docker login ghcr.io --username "$GHCR_USERNAME" --password-stdin >/dev/null
unset GHCR_TOKEN

update_image_tag() {
  local key=$1 value=$2 temporary
  temporary=$(mktemp "$DEPLOY_ROOT/.image.env.XXXXXX")
  awk -F= -v key="$key" '$1 != key { print }' "$IMAGE_ENV" >"$temporary"
  printf '%s=%s\n' "$key" "$value" >>"$temporary"
  chmod 600 "$temporary"
  mv "$temporary" "$IMAGE_ENV"
}

update_image_tag BACKEND_IMAGE_TAG "$1"
update_image_tag FRONTEND_IMAGE_TAG "$1"

cd "$DEPLOY_ROOT"
docker compose --env-file "$RUNTIME_ENV" --env-file "$IMAGE_ENV" -f "$COMPOSE_FILE" pull backend frontend
docker compose --profile worker --env-file "$RUNTIME_ENV" --env-file "$IMAGE_ENV" -f "$COMPOSE_FILE" up -d --wait --wait-timeout 300
curl --fail --silent --show-error --max-time 10 http://127.0.0.1:15101/healthz >/dev/null
docker image prune --force --filter until=168h >/dev/null
