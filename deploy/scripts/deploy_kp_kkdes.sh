#!/usr/bin/env bash
set -euo pipefail

# KoPilot Backend Installer & Updater for kp.kkdes.co.ke (Apache + Certbot + Docker Compose)
# Safe for multi-tenant VPS: binds API to 127.0.0.1:8440 and leaves host Apache on 80/443.

DOMAIN="${KOPILOT_DOMAIN:-kp.kkdes.co.ke}"
APP_DIR="${KOPILOT_APP_DIR:-/opt/kopilot}"
GIT_REPO_URL="${KOPILOT_GIT_REPO:-}"
GIT_BRANCH="${KOPILOT_GIT_BRANCH:-main}"
CERTBOT_EMAIL="${CERTBOT_EMAIL:-admin@kkdes.co.ke}"

if [[ $EUID -ne 0 ]]; then
  echo "Please run as root (e.g. sudo bash $0)" >&2
  exit 1
fi

echo "[1/7] Checking prerequisites..."
for cmd in git docker apache2ctl a2enmod a2ensite certbot curl openssl; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    exit 1
  fi
done

echo "[2/7] Syncing repository in ${APP_DIR}..."
if [[ -d "${APP_DIR}/.git" ]]; then
  git -C "$APP_DIR" fetch origin "$GIT_BRANCH"
  git -C "$APP_DIR" checkout "$GIT_BRANCH"
  git -C "$APP_DIR" pull --ff-only origin "$GIT_BRANCH"
else
  if [[ -z "$GIT_REPO_URL" ]]; then
    echo "KOPILOT_GIT_REPO must be set for initial clone into ${APP_DIR}" >&2
    exit 1
  fi
  git clone --branch "$GIT_BRANCH" "$GIT_REPO_URL" "$APP_DIR"
fi

DEPLOY_DIR="${APP_DIR}/deploy"
ENV_FILE="${DEPLOY_DIR}/.env"

echo "[3/7] Preparing environment file..."
if [[ ! -f "$ENV_FILE" ]]; then
  cp "${DEPLOY_DIR}/.env.example" "$ENV_FILE"
  PG_PASS="$(openssl rand -hex 24)"
  JWT_KEY="$(openssl rand -hex 32)"
  sed -i "s|replace-with-strong-random-password|${PG_PASS}|g" "$ENV_FILE"
  sed -i "s|replace-with-at-least-32-character-random-secret-key|${JWT_KEY}|g" "$ENV_FILE"
  sed -i "s|^ALLOWED_ORIGINS=.*|ALLOWED_ORIGINS=[\"https://${DOMAIN}\"]|g" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo "Created ${ENV_FILE} with generated database & JWT secrets."
  echo "IMPORTANT: Add OPENAI_API_KEY and GOOGLE_MAPS_API_KEY to ${ENV_FILE} when ready."
fi

echo "[4/7] Building and starting Docker services (postgres, redis, api)..."
cd "$DEPLOY_DIR"
docker compose build --no-cache api
docker compose up -d postgres redis api
docker image prune -f

echo "[5/7] Configuring Apache reverse proxy for ${DOMAIN}..."
a2enmod proxy proxy_http headers rewrite ssl >/dev/null
cp "${DEPLOY_DIR}/apache/kp.kkdes.co.ke.conf" "/etc/apache2/sites-available/${DOMAIN}.conf"
sed -i "s|kp.kkdes.co.ke|${DOMAIN}|g" "/etc/apache2/sites-available/${DOMAIN}.conf"
a2ensite "${DOMAIN}.conf" >/dev/null
apache2ctl configtest
systemctl reload apache2

echo "[6/7] Configuring SSL with Certbot..."
if [[ ! -d "/etc/letsencrypt/live/${DOMAIN}" ]]; then
  certbot --apache \
    -d "$DOMAIN" \
    --non-interactive \
    --agree-tos \
    -m "$CERTBOT_EMAIL" \
    --redirect
else
  echo "Certificate already exists for ${DOMAIN}; verifying Apache SSL reload..."
fi

echo "[7/7] Restarting services and verifying health..."
docker compose restart api
systemctl reload apache2

sleep 3
docker compose ps
curl -fsS "http://127.0.0.1:8440/health" && echo ""
curl -fsS "https://${DOMAIN}/health" && echo ""
echo "Deployment complete for https://${DOMAIN}"
