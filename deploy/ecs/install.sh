#!/usr/bin/env bash
set -euo pipefail
APP_ROOT="/opt/website"
BACKEND_DIR="$APP_ROOT/backend"
FRONTEND_DIR="$APP_ROOT/frontend"
UPDATE_DIR="${APP_UPDATE_STORAGE_DIR:-$APP_ROOT/updates}"
mkdir -p "$BACKEND_DIR" "$FRONTEND_DIR" "$UPDATE_DIR"
cp website.jar "$BACKEND_DIR/website.jar"
if [ -d dist ]; then
  rm -rf "$FRONTEND_DIR/dist"
  mkdir -p "$FRONTEND_DIR/dist"
  cp -a dist/. "$FRONTEND_DIR/dist/"
fi
if [ ! -f "$BACKEND_DIR/.env" ]; then
  cat > "$BACKEND_DIR/.env" <<'ENV'
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/website?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=
JWT_SECRET=Y2hhbmdlLXRoaXMtdG8tYS1zdHJvbmctc2VjcmV0LWtleS1hdC1sZWFzdC0zMi1ieXRlcw==
OREATE_PROXY_URL=http://127.0.0.1:8000
OREATE_PROXY_API_KEY=
APP_UPDATE_STORAGE_DIR=/opt/website/updates
APP_UPDATE_BASE_URL=https://api.751152.xyz/updates
ENV
  chmod 600 "$BACKEND_DIR/.env"
  echo "Created $BACKEND_DIR/.env, please edit DB/JWT/Oreate settings before production use."
fi
if grep -q '^JWT_SECRET=change-this-to-a-strong-base64-secret$' "$BACKEND_DIR/.env"; then
  sed -i 's#^JWT_SECRET=.*#JWT_SECRET=Y2hhbmdlLXRoaXMtdG8tYS1zdHJvbmctc2VjcmV0LWtleS1hdC1sZWFzdC0zMi1ieXRlcw==#' "$BACKEND_DIR/.env"
  chmod 600 "$BACKEND_DIR/.env"
  echo "Replaced invalid JWT_SECRET placeholder."
fi
cp website-api.service /etc/systemd/system/website-api.service
systemctl daemon-reload
systemctl enable website-api
systemctl restart website-api
systemctl --no-pager --full status website-api || true
