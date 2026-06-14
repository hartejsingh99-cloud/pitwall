#!/usr/bin/env bash
# Download the PINNED f1db release (f1db.version), verify its checksum, and unzip the 70 MB f1db.db
# into composeResources/ at build time. The DB is never committed (gitignored) — git stays clean and
# the binary lives only inside built artifacts / GitHub Releases. Idempotent: a present DB is trusted.
set -euo pipefail

VERSION="$(cat f1db.version)"                                   # single source of truth, e.g. v2026.6.3
BASE="https://github.com/f1db/f1db/releases/download/${VERSION}"
DEST="composeApp/src/commonMain/composeResources/files"
DB="${DEST}/f1db.db"

mkdir -p "${DEST}"

if [[ -f "${DB}" ]]; then
  echo "f1db.db already present; skipping download (local-dev / vendored case)."
  exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT
curl -fsSL "${BASE}/f1db-sqlite.zip"      -o "${tmp}/f1db-sqlite.zip"
curl -fsSL "${BASE}/checksums_sha256.txt" -o "${tmp}/checksums_sha256.txt"

# Verify only the f1db-sqlite.zip line (the file ships several sqlite variants). shasum -a 256 -c
# exists on both ubuntu-latest and macos-latest runners (BSD vs GNU sha256sum differ; shasum doesn't).
( cd "${tmp}" && grep -E '  f1db-sqlite\.zip$' checksums_sha256.txt | shasum -a 256 -c - )

unzip -o "${tmp}/f1db-sqlite.zip" -d "${tmp}"
mv "${tmp}/f1db.db" "${DB}"                                      # verified: zip contains f1db.db at root
echo "Bundled f1db ${VERSION} -> ${DB}"
