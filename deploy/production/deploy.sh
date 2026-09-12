#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
FRONTEND_ROOT="${FRONTEND_ROOT:-$BACKEND_ROOT/../../vs/website}"
SSH_HOST=""
SSH_USER="root"
SSH_PORT="22"
SSH_IDENTITY=""
RELEASE_ID=""
ALLOW_DIRTY="false"
SKIP_TESTS="false"
SKIP_INSTALL="false"
BUILD_ONLY="false"

usage() {
  cat <<'EOF'
Usage:
  deploy/production/deploy.sh --host <ecs-host> [options]
  deploy/production/deploy.sh --build-only [options]

Options:
  --host <host>           ECS IP or hostname (required unless --build-only)
  --user <user>           SSH user, default: root
  --port <port>           SSH port, default: 22
  --identity <path>       SSH private key
  --frontend <path>       Frontend repository path
  --release <id>          Release id; default: timestamp plus git SHA
  --allow-dirty           Allow uncommitted files (recorded in release metadata)
  --skip-tests            Skip backend tests; frontend type checking still runs
  --skip-install          Reuse frontend node_modules instead of npm ci
  --build-only            Build the archive without uploading or activating it
  -h, --help              Show this help
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --host) SSH_HOST="${2:-}"; shift 2 ;;
    --user) SSH_USER="${2:-}"; shift 2 ;;
    --port) SSH_PORT="${2:-}"; shift 2 ;;
    --identity) SSH_IDENTITY="${2:-}"; shift 2 ;;
    --frontend) FRONTEND_ROOT="${2:-}"; shift 2 ;;
    --release) RELEASE_ID="${2:-}"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY="true"; shift ;;
    --skip-tests) SKIP_TESTS="true"; shift ;;
    --skip-install) SKIP_INSTALL="true"; shift ;;
    --build-only) BUILD_ONLY="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

[ -d "$FRONTEND_ROOT" ] || die "frontend repository not found: $FRONTEND_ROOT"
[ -f "$FRONTEND_ROOT/package-lock.json" ] || die "invalid frontend repository: $FRONTEND_ROOT"
[ -f "$BACKEND_ROOT/pom.xml" ] || die "invalid backend repository: $BACKEND_ROOT"

for command_name in git mvn npm node tar; do
  command -v "$command_name" >/dev/null 2>&1 || die "missing command: $command_name"
done
if [ "$BUILD_ONLY" != "true" ]; then
  [ -n "$SSH_HOST" ] || die "--host is required unless --build-only is used"
  for command_name in ssh scp; do
    command -v "$command_name" >/dev/null 2>&1 || die "missing command: $command_name"
  done
fi

BACKEND_SHA="$(git -C "$BACKEND_ROOT" rev-parse --short=12 HEAD)"
FRONTEND_SHA="$(git -C "$FRONTEND_ROOT" rev-parse --short=12 HEAD)"
BACKEND_DIRTY="$(git -C "$BACKEND_ROOT" status --porcelain)"
FRONTEND_DIRTY="$(git -C "$FRONTEND_ROOT" status --porcelain)"

if [ "$ALLOW_DIRTY" != "true" ] && { [ -n "$BACKEND_DIRTY" ] || [ -n "$FRONTEND_DIRTY" ]; }; then
  echo "Backend changes:" >&2
  git -C "$BACKEND_ROOT" status --short >&2
  echo "Frontend changes:" >&2
  git -C "$FRONTEND_ROOT" status --short >&2
  die "working tree is dirty; commit the release or pass --allow-dirty for an emergency build"
fi

if [ -z "$RELEASE_ID" ]; then
  RELEASE_ID="$(date -u +%Y%m%dT%H%M%SZ)-${BACKEND_SHA}-${FRONTEND_SHA}"
fi
case "$RELEASE_ID" in
  *[!A-Za-z0-9._-]*) die "release id may only contain letters, numbers, dot, underscore, and dash" ;;
esac

echo "==> Backend:  $BACKEND_ROOT ($BACKEND_SHA)"
echo "==> Frontend: $FRONTEND_ROOT ($FRONTEND_SHA)"
echo "==> Release:  $RELEASE_ID"

if [ "$SKIP_TESTS" != "true" ]; then
  echo "==> Running backend tests"
  (cd "$BACKEND_ROOT" && mvn test)
fi

echo "==> Packaging backend"
(cd "$BACKEND_ROOT" && mvn -DskipTests package)

if [ "$SKIP_INSTALL" != "true" ]; then
  echo "==> Installing locked frontend dependencies"
  (cd "$FRONTEND_ROOT" && npm ci)
fi

echo "==> Building frontend"
(cd "$FRONTEND_ROOT" && npm run build)
node --check "$FRONTEND_ROOT/server/ecs-server.mjs"

JAR_PATH="$BACKEND_ROOT/target/website-0.0.1-SNAPSHOT.jar"
[ -f "$JAR_PATH" ] || die "backend artifact not found: $JAR_PATH"
[ -f "$FRONTEND_ROOT/dist/index.html" ] || die "frontend dist/index.html was not built"

STAGE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/website-release.XXXXXX")"
cleanup() {
  rm -rf "$STAGE_DIR"
}
trap cleanup EXIT

PAYLOAD_DIR="$STAGE_DIR/$RELEASE_ID"
mkdir -p "$PAYLOAD_DIR/backend" "$PAYLOAD_DIR/frontend" "$PAYLOAD_DIR/meta" "$PAYLOAD_DIR/ops"
cp "$JAR_PATH" "$PAYLOAD_DIR/backend/website.jar"
cp -R "$FRONTEND_ROOT/dist" "$PAYLOAD_DIR/frontend/dist"
cp -R "$FRONTEND_ROOT/server" "$PAYLOAD_DIR/frontend/server"
cp "$SCRIPT_DIR/remote-activate.sh" "$PAYLOAD_DIR/ops/remote-activate.sh"
cp "$SCRIPT_DIR/remote-rollback.sh" "$PAYLOAD_DIR/ops/remote-rollback.sh"
cp "$SCRIPT_DIR/website-api.service" "$PAYLOAD_DIR/ops/website-api.service"
cp "$SCRIPT_DIR/website-ecs.service" "$PAYLOAD_DIR/ops/website-ecs.service"
cp "$SCRIPT_DIR/nginx.conf" "$PAYLOAD_DIR/ops/nginx.conf"
cp "$SCRIPT_DIR/backend.env.example" "$PAYLOAD_DIR/ops/backend.env.example"
cp "$SCRIPT_DIR/frontend.env.example" "$PAYLOAD_DIR/ops/frontend.env.example"

{
  echo "RELEASE_ID=$RELEASE_ID"
  echo "CREATED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "BACKEND_SHA=$BACKEND_SHA"
  echo "FRONTEND_SHA=$FRONTEND_SHA"
  echo "BACKEND_DIRTY=$([ -n "$BACKEND_DIRTY" ] && echo true || echo false)"
  echo "FRONTEND_DIRTY=$([ -n "$FRONTEND_DIRTY" ] && echo true || echo false)"
} > "$PAYLOAD_DIR/meta/release.env"

ARTIFACT_DIR="$BACKEND_ROOT/target/releases"
mkdir -p "$ARTIFACT_DIR"
ARCHIVE="$ARTIFACT_DIR/website-release-$RELEASE_ID.tar.gz"
tar -C "$STAGE_DIR" -czf "$ARCHIVE" "$RELEASE_ID"
echo "==> Archive: $ARCHIVE"

if [ "$BUILD_ONLY" = "true" ]; then
  echo "==> Build-only release complete"
  exit 0
fi

SSH_ARGS=(-p "$SSH_PORT")
SCP_ARGS=(-P "$SSH_PORT")
if [ -n "$SSH_IDENTITY" ]; then
  SSH_ARGS+=(-i "$SSH_IDENTITY")
  SCP_ARGS+=(-i "$SSH_IDENTITY")
fi
REMOTE="${SSH_USER}@${SSH_HOST}"
REMOTE_ARCHIVE="/tmp/website-release-$RELEASE_ID.tar.gz"

echo "==> Uploading release to $REMOTE"
scp "${SCP_ARGS[@]}" "$ARCHIVE" "$REMOTE:$REMOTE_ARCHIVE"

echo "==> Activating release"
REMOTE_SUDO=""
if [ "$SSH_USER" != "root" ]; then
  REMOTE_SUDO="sudo -n "
fi
ssh "${SSH_ARGS[@]}" "$REMOTE" \
  "${REMOTE_SUDO}mkdir -p /opt/website/releases && ${REMOTE_SUDO}tar -xzf '$REMOTE_ARCHIVE' -C /opt/website/releases && ${REMOTE_SUDO}bash '/opt/website/releases/$RELEASE_ID/ops/remote-activate.sh' '$RELEASE_ID'"

echo "==> Release $RELEASE_ID is active"
echo "    API:      https://api.751152.xyz/health"
echo "    Frontend: https://hi.751152.xyz/"
