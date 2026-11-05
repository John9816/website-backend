#!/usr/bin/env bash
set -euo pipefail

echo 'Obsolete deployment script. Use deploy/production/deploy.sh; see docs/production-release.md.' >&2
echo 'The old flow overwrote the live JAR and required an untracked manual restart.' >&2
exit 1
