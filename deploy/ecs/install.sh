#!/usr/bin/env bash
set -euo pipefail
APP_ROOT="/opt/website"
BACKEND_DIR="$APP_ROOT/backend"
FRONTEND_DIR="$APP_ROOT/frontend"
UPDATE_DIR="${APP_UPDATE_STORAGE_DIR:-$APP_ROOT/updates}"
mkdir -p "$BACKEND_DIR" "$FRONTEND_DIR" "$UPDATE_DIR"
if [ ! -f "$BACKEND_DIR/.env" ]; then
  echo "Create $BACKEND_DIR/.env with DB and JWT credentials (mode 600) before installing." >&2
  exit 1
fi
chmod 600 "$BACKEND_DIR/.env"
grep -Eq '^SPRING_DATASOURCE_PASSWORD=.+' "$BACKEND_DIR/.env" || { echo 'Database password is missing.' >&2; exit 1; }
jwt_secret="$(sed -n 's/^JWT_SECRET=//p' "$BACKEND_DIR/.env" | head -n 1)"
if [ -z "$jwt_secret" ] || [ "$jwt_secret" = 'change-this-to-a-strong-base64-secret' ] || \
   [ "$(printf '%s' "$jwt_secret" | base64 --decode 2>/dev/null || true)" = 'change-this-to-a-strong-secret-key-at-least-32-bytes' ]; then
  echo 'Set a unique JWT_SECRET in the protected .env before installing.' >&2
  exit 1
fi
cp website.jar "$BACKEND_DIR/website.jar"
if [ -d dist ]; then
  rm -rf "$FRONTEND_DIR/dist"
  mkdir -p "$FRONTEND_DIR/dist"
  cp -a dist/. "$FRONTEND_DIR/dist/"
fi
cp website-api.service /etc/systemd/system/website-api.service
systemctl daemon-reload
systemctl enable website-api
systemctl restart website-api
systemctl --no-pager --full status website-api || true
