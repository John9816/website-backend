#!/usr/bin/env bash
set -euo pipefail

echo 'Obsolete deployment script. Use deploy/production/deploy.sh; see docs/production-release.md.' >&2
echo 'Review and apply each database migration separately after a verified backup.' >&2
exit 1
