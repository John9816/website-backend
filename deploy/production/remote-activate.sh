#!/usr/bin/env bash
set -Eeuo pipefail

RELEASE_ID="${1:-}"
APP_ROOT="/opt/website"
RELEASE_DIR="$APP_ROOT/releases/$RELEASE_ID"
CURRENT_LINK="$APP_ROOT/current"
PREVIOUS_LINK="$APP_ROOT/previous"
BACKEND_ENV="$APP_ROOT/backend/.env"
FRONTEND_ENV="$APP_ROOT/.env.ecs"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] || die "remote activation must run as root"
[ -n "$RELEASE_ID" ] || die "release id is required"
case "$RELEASE_ID" in
  *[!A-Za-z0-9._-]*|.|..) die "invalid release id" ;;
esac
for command_name in curl java node systemctl; do
  command -v "$command_name" >/dev/null 2>&1 || die "missing server command: $command_name"
done
[ -f "$RELEASE_DIR/backend/website.jar" ] || die "backend artifact is missing"
[ -f "$RELEASE_DIR/frontend/dist/index.html" ] || die "frontend artifact is missing"
[ -f "$RELEASE_DIR/frontend/server/ecs-server.mjs" ] || die "frontend server is missing"

mkdir -p "$APP_ROOT/backend" "$APP_ROOT/updates" "$APP_ROOT/config-examples"
install -d -m 0750 \
  "$APP_ROOT/shared" \
  "$APP_ROOT/shared/uploads" \
  "$APP_ROOT/shared/uploads/images" \
  "$APP_ROOT/shared/uploads/avatars"
if [ -d "$CURRENT_LINK/backend/uploads" ] && [ ! -L "$CURRENT_LINK/backend/uploads" ]; then
  cp -an \
    "$CURRENT_LINK/backend/uploads/." \
    "$APP_ROOT/shared/uploads/"
fi
[ ! -e "$RELEASE_DIR/backend/uploads" ] || [ -L "$RELEASE_DIR/backend/uploads" ] \
  || die "new release must not contain private uploads"
ln -sfn "$APP_ROOT/shared/uploads" "$RELEASE_DIR/backend/uploads"
cp "$RELEASE_DIR/ops/backend.env.example" "$APP_ROOT/config-examples/backend.env.example"
cp "$RELEASE_DIR/ops/frontend.env.example" "$APP_ROOT/config-examples/frontend.env.example"
cp "$RELEASE_DIR/ops/nginx.conf" "$APP_ROOT/config-examples/nginx.conf"

if [ ! -f "$BACKEND_ENV" ] || [ ! -f "$FRONTEND_ENV" ]; then
  cat >&2 <<EOF
Production environment files are missing. Examples were installed at:
  $APP_ROOT/config-examples/backend.env.example
  $APP_ROOT/config-examples/frontend.env.example

Create these files with real secrets, chmod them to 600, then run the deploy command again:
  $BACKEND_ENV
  $FRONTEND_ENV
EOF
  exit 12
fi

if ! grep -q '^WEBSITE_SHARED_DIR=..' "$BACKEND_ENV"; then
  printf '\nWEBSITE_SHARED_DIR=/opt/website/shared\n' >> "$BACKEND_ENV"
fi
if ! grep -q '^AVATAR_UPLOAD_DIR=..' "$BACKEND_ENV"; then
  printf '\nAVATAR_UPLOAD_DIR=/opt/website/shared/uploads/avatars\n' >> "$BACKEND_ENV"
fi

chmod 600 "$BACKEND_ENV" "$FRONTEND_ENV"
grep -q '^SPRING_DATASOURCE_PASSWORD=..' "$BACKEND_ENV" || die "SPRING_DATASOURCE_PASSWORD is empty or missing"
grep -q '^JWT_SECRET=..' "$BACKEND_ENV" || die "JWT_SECRET is empty or missing"
if grep -q 'replace-with-' "$BACKEND_ENV"; then
  die "backend environment still contains replace-with-* placeholders"
fi
if grep -q 'Y2hhbmdlLXRoaXMtdG8tYS1zdHJvbmctc2VjcmV0LWtleS1hdC1sZWFzdC0zMi1ieXRlcw==' "$BACKEND_ENV"; then
    die "the development JWT secret cannot be used in production"
fi
grep -q '^BACKEND_URL=..' "$FRONTEND_ENV" || die "BACKEND_URL is empty or missing"

cp "$RELEASE_DIR/ops/website-api.service" /etc/systemd/system/website-api.service
cp "$RELEASE_DIR/ops/website-ecs.service" /etc/systemd/system/website-ecs.service
systemctl daemon-reload
systemctl enable website-api.service website-ecs.service >/dev/null

OLD_RELEASE=""
if [ -L "$CURRENT_LINK" ]; then
  OLD_RELEASE="$(readlink -f "$CURRENT_LINK")"
fi

rollback() {
  exit_status=$?
  trap - EXIT
  [ "$exit_status" -ne 0 ] || return 0
  if [ -n "$OLD_RELEASE" ] && [ -d "$OLD_RELEASE" ]; then
    echo "Activation failed; restoring $OLD_RELEASE" >&2
    ln -sfn "$OLD_RELEASE" "$CURRENT_LINK.next"
    mv -Tf "$CURRENT_LINK.next" "$CURRENT_LINK"
    systemctl restart website-api.service website-ecs.service || true
  fi
  exit "$exit_status"
}
trap rollback EXIT

if [ -n "$OLD_RELEASE" ] && [ -d "$OLD_RELEASE" ]; then
  ln -sfn "$OLD_RELEASE" "$PREVIOUS_LINK"
fi
ln -sfn "$RELEASE_DIR" "$CURRENT_LINK.next"
mv -Tf "$CURRENT_LINK.next" "$CURRENT_LINK"

systemctl restart website-api.service
for attempt in $(seq 1 30); do
  if curl --connect-timeout 2 --max-time 5 --fail --silent http://127.0.0.1:8090/health >/dev/null; then
    break
  fi
  [ "$attempt" -lt 30 ] || die "backend health check failed"
  sleep 1
done

systemctl restart website-ecs.service
for attempt in $(seq 1 20); do
  if curl --connect-timeout 2 --max-time 5 --fail --silent http://127.0.0.1:3000/healthz >/dev/null; then
    break
  fi
  [ "$attempt" -lt 20 ] || die "frontend health check failed"
  sleep 1
done

curl --connect-timeout 2 --max-time 10 --fail --silent --show-error http://127.0.0.1:3000/api/public/nav >/dev/null
trap - EXIT

echo "Activated release $RELEASE_ID"
systemctl --no-pager --full status website-api.service website-ecs.service || true
