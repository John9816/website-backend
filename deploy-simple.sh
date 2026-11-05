#!/usr/bin/env bash
set -euo pipefail

echo 'Obsolete deployment script. Use deploy/production/deploy.sh; see docs/production-release.md.' >&2
echo 'The old flow committed arbitrary changes and restarted a legacy service.' >&2
exit 1
