#!/usr/bin/env bash
set -Eeuo pipefail

readonly DEPLOY_ROOT=/opt/kmarket
readonly COMPOSE_FILE="$DEPLOY_ROOT/compose.prod.yaml"
readonly RUNTIME_ENV="$DEPLOY_ROOT/runtime.env"
readonly IMAGE_ENV="$DEPLOY_ROOT/image.env"
readonly NGINX_SOURCE="$DEPLOY_ROOT/nginx-https.conf"
readonly NGINX_TARGET=/etc/nginx/conf.d/kmarket.conf
readonly TLS_CERTIFICATE=/etc/letsencrypt/live/api.kartkr.cloud/fullchain.pem
readonly TLS_PRIVATE_KEY=/etc/letsencrypt/live/api.kartkr.cloud/privkey.pem

if [[ $# -ne 1 || ! "$1" =~ ^[0-9a-f]{40}$ ]]; then
  echo "40자 Git 커밋 SHA가 필요합니다." >&2
  exit 2
fi
: "${GHCR_USERNAME:?required}"
: "${GHCR_TOKEN:?required}"

exec 9>"$DEPLOY_ROOT/.deploy.lock"
flock 9
umask 077

image_backup=$(mktemp "$DEPLOY_ROOT/.image.env.previous.XXXXXX")
runtime_backup=$(mktemp "$DEPLOY_ROOT/.runtime.env.previous.XXXXXX")
rollback_override=$(mktemp "$DEPLOY_ROOT/.backend.rollback.XXXXXX.yaml")
cp "$IMAGE_ENV" "$image_backup"
cp "$RUNTIME_ENV" "$runtime_backup"
previous_image=$(docker inspect --format '{{.Image}}' kmarket-backend-1)
printf 'services:\n  backend:\n    image: "%s"\n' "$previous_image" >"$rollback_override"
printf '%s\n' "$previous_image" >"$DEPLOY_ROOT/backend-last-good.image"
deployment_verified=0
cleanup() {
  local status=$?
  trap - EXIT
  if (( status != 0 && deployment_verified == 0 )); then
    cp "$image_backup" "$IMAGE_ENV"
    cp "$runtime_backup" "$RUNTIME_ENV"
    docker compose --env-file "$RUNTIME_ENV" --env-file "$IMAGE_ENV" -f "$COMPOSE_FILE" \
      -f "$rollback_override" up -d --no-deps --wait --wait-timeout 900 backend || true
  fi
  rm -f "$image_backup" "$runtime_backup" "$rollback_override"
  exit "$status"
}
trap cleanup EXIT

if [[ -f "$DEPLOY_ROOT/runtime.incoming.env" ]]; then
  python3 "$DEPLOY_ROOT/merge-runtime.py"
fi

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

# 과거 운영 Frontend 태그가 남아 있어도 다음 배포부터 참조하지 않는다.
temporary=$(mktemp "$DEPLOY_ROOT/.image.env.XXXXXX")
awk -F= '$1 != "FRONTEND_IMAGE_TAG" { print }' "$IMAGE_ENV" >"$temporary"
chmod 600 "$temporary"
mv "$temporary" "$IMAGE_ENV"

runtime_env_temporary=$(mktemp "$DEPLOY_ROOT/.runtime.env.XXXXXX")
awk -F= '$1 != "KMARKET_AI_HANA_EXPECTED_COMMIT" && $1 != "KMARKET_AI_TITLE_TRANSLATION_PROMPT_VERSION" && $1 != "KMARKET_AI_NEWS_NARRATIVE_PROMPT_VERSION" && $1 != "KMARKET_AI_DISCLOSURE_SECTION_PROMPT_VERSION" && $1 != "KMARKET_AI_FILING_SUMMARY_PROMPT_VERSION" && $1 != "KMARKET_AI_TAX_DOCUMENT_PROMPT_VERSION" { print }' "$RUNTIME_ENV" \
  >"$runtime_env_temporary"
printf '%s\n' \
  'KMARKET_AI_NEWS_NARRATIVE_PROMPT_VERSION=news-narrative-v12' \
  'KMARKET_AI_DISCLOSURE_SECTION_PROMPT_VERSION=disclosure-section-translation-v7' \
  'KMARKET_AI_FILING_SUMMARY_PROMPT_VERSION=filing-summary-v3' \
  'KMARKET_AI_TAX_DOCUMENT_PROMPT_VERSION=kmarket-tax-ocr-e2e-v1' \
  >>"$runtime_env_temporary"
chmod 600 "$runtime_env_temporary"
mv "$runtime_env_temporary" "$RUNTIME_ENV"

reload_edge_nginx() {
  local backup
  backup=$(mktemp "$DEPLOY_ROOT/.nginx.conf.backup.XXXXXX")

  if ! sudo -n test -r "$TLS_CERTIFICATE" || ! sudo -n test -r "$TLS_PRIVATE_KEY"; then
    echo "api.kartkr.cloud TLS 인증서가 없습니다." >&2
    rm -f "$backup"
    return 1
  fi

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
docker compose --env-file "$RUNTIME_ENV" --env-file "$IMAGE_ENV" -f "$COMPOSE_FILE" pull backend
docker compose --env-file "$RUNTIME_ENV" --env-file "$IMAGE_ENV" -f "$COMPOSE_FILE" up -d --no-deps --wait --wait-timeout 900 backend
curl --fail --silent --show-error --max-time 10 http://127.0.0.1:15102/actuator/health >/dev/null
reload_edge_nginx
deployment_verified=1

# 새 마운트가 정상 기동된 뒤에만 이전 경로 호환 심볼릭 링크를 제거한다.
legacy_model_runtime="$DEPLOY_ROOT/hannah-runtime"
if [[ -L "$legacy_model_runtime" ]]; then
  if [[ "$(readlink "$legacy_model_runtime")" != "kmarket-model-runtime" ]]; then
    echo "알 수 없는 기존 모델 런타임 링크가 있습니다." >&2
    exit 1
  fi
  rm "$legacy_model_runtime"
fi

sudo -n install -o root -g root -m 0644 "$DEPLOY_ROOT/kart-image-retention.service" "$DEPLOY_ROOT/kart-image-retention.timer" /etc/systemd/system/
sudo -n systemctl daemon-reload
sudo -n systemctl enable --now kart-image-retention.timer
python3 "$DEPLOY_ROOT/retain-images.py" --apply --already-locked
