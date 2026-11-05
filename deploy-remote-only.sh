#!/usr/bin/env bash
set -euo pipefail

echo 'Obsolete deployment script. Use deploy/production/deploy.sh; see docs/production-release.md.' >&2
echo 'The old flow pinned a stale branch and ran migrations automatically.' >&2
exit 1
