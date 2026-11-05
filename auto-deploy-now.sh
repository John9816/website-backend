#!/usr/bin/env bash
set -euo pipefail

echo 'Obsolete deployment script. Use deploy/production/deploy.sh; see docs/production-release.md.' >&2
echo 'The old flow committed and pushed unrelated local changes, then ran unreviewed migrations.' >&2
exit 1
