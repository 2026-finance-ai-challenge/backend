#!/usr/bin/env bash
set -Eeuo pipefail

readonly DEPLOY_ROOT=/opt/kmarket
readonly COMPOSE_FILE="$DEPLOY_ROOT/compose.prod.yaml"
readonly RUNTIME_ENV="$DEPLOY_ROOT/runtime.env"
readonly IMAGE_ENV="$DEPLOY_ROOT/image.env"
readonly NGINX_SOURCE="$DEPLOY_ROOT/nginx-https.conf"
readonly NGINX_TARGET=/etc/nginx/conf.d/kmarket.conf

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

reload_edge_nginx() {
  local backup
  backup=$(mktemp "$DEPLOY_ROOT/.nginx.conf.backup.XXXXXX")

  if sudo -n test -f "$NGINX_TARGET"; then
    sudo -n cp "$NGINX_TARGET" "$backup"
  fi

  sudo -n install -o root -g root -m 0644 "$NGINX_SOURCE" "$NGINX_TARGET"
  if ! sudo -n nginx -t; then
    if [[ -s "$backup" ]]; then
      sudo -n install -o root -g root -m 0644 "$backup" "$NGINX_TARGET"
    else
      sudo -n rm -f "$NGINX_TARGET"
    fi
    sudo -n nginx -t
    rm -f "$backup"
    return 1
  fi

  if ! sudo -n systemctl reload nginx; then
    if [[ -s "$backup" ]]; then
      sudo -n install -o root -g root -m 0644 "$backup" "$NGINX_TARGET"
    else
      sudo -n rm -f "$NGINX_TARGET"
    fi
    sudo -n nginx -t
    sudo -n systemctl reload nginx
    rm -f "$backup"
    return 1
  fi
  rm -f "$backup"
}

cd "$DEPLOY_ROOT"
docker compose --env-file "$RUNTIME_ENV" --env-file "$IMAGE_ENV" -f "$COMPOSE_FILE" pull backend frontend
docker compose --profile worker --env-file "$RUNTIME_ENV" --env-file "$IMAGE_ENV" -f "$COMPOSE_FILE" up -d --wait --wait-timeout 900
curl --fail --silent --show-error --max-time 10 http://127.0.0.1:15101/healthz >/dev/null
reload_edge_nginx
docker image prune --force --filter until=168h >/dev/null
