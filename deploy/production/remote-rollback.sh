#!/usr/bin/env bash
set -Eeuo pipefail

APP_ROOT="/opt/website"
CURRENT_LINK="$APP_ROOT/current"
TARGET="${1:-previous}"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] || die "rollback must run as root"
[ -L "$CURRENT_LINK" ] || die "no active release exists"

if [ "$TARGET" = "previous" ]; then
  [ -L "$APP_ROOT/previous" ] || die "no previous release exists"
  TARGET_DIR="$(readlink -f "$APP_ROOT/previous")"
else
  case "$TARGET" in
    *[!A-Za-z0-9._-]*) die "invalid release id" ;;
  esac
  TARGET_DIR="$APP_ROOT/releases/$TARGET"
fi

[ -f "$TARGET_DIR/backend/website.jar" ] || die "target backend artifact is missing: $TARGET_DIR"
[ -f "$TARGET_DIR/frontend/dist/index.html" ] || die "target frontend artifact is missing: $TARGET_DIR"

OLD_RELEASE="$(readlink -f "$CURRENT_LINK")"
ln -sfn "$TARGET_DIR" "$CURRENT_LINK.next"
mv -Tf "$CURRENT_LINK.next" "$CURRENT_LINK"

if ! systemctl restart website-api.service website-ecs.service \
  || ! curl --retry 20 --retry-delay 1 --retry-connrefused --fail --silent --show-error http://127.0.0.1:8090/health >/dev/null \
  || ! curl --retry 10 --retry-delay 1 --retry-connrefused --fail --silent --show-error http://127.0.0.1:3000/healthz >/dev/null; then
  echo "Rollback target failed health checks; restoring $OLD_RELEASE" >&2
  ln -sfn "$OLD_RELEASE" "$CURRENT_LINK.next"
  mv -Tf "$CURRENT_LINK.next" "$CURRENT_LINK"
  systemctl restart website-api.service website-ecs.service || true
  exit 1
fi

ln -sfn "$OLD_RELEASE" "$APP_ROOT/previous"
echo "Rolled back to $(basename "$TARGET_DIR")"
