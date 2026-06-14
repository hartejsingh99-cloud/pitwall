#!/usr/bin/env bash
# Provide telemetry.db at build time. Like f1db.db it is gitignored (no data blobs in history).
# Order of preference:
#   1. already present (local-dev / vendored real bake)  -> trust it
#   2. $TELEMETRY_URL set (a hosted real-bake asset)      -> download it
#   3. otherwise                                          -> generate the synthetic sample (stdlib-only)
# So CI is always green and reproducible at $0 with no network needed for telemetry, while leaving a
# clean hook (TELEMETRY_URL) to ship a real bake hosted as a GitHub Release asset later.
set -euo pipefail

DEST="composeApp/src/commonMain/composeResources/files"
DB="${DEST}/telemetry.db"

mkdir -p "${DEST}"

if [[ -f "${DB}" ]]; then
  echo "telemetry.db already present; skipping."
  exit 0
fi

ABS="$(cd "${DEST}" && pwd)/telemetry.db"

if [[ -n "${TELEMETRY_URL:-}" ]]; then
  echo "Downloading telemetry.db from \$TELEMETRY_URL"
  curl -fsSL "${TELEMETRY_URL}" -o "${ABS}"
else
  echo "No \$TELEMETRY_URL; generating synthetic sample telemetry.db (stdlib only)."
  ( cd tools/bake && python3 make_sample_db.py --out "${ABS}" )
fi
echo "telemetry.db ready -> ${DB}"
