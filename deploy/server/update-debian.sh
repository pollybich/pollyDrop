#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != "--frontend-from-source" ] && [ "$1" != "--frontend-from-release" ]; }; then
  echo "Usage: $0 [--frontend-from-source|--frontend-from-release]" >&2; exit 1
fi
if [ "$(id -u)" -ne 0 ]; then echo 'Run this script as root on the server.' >&2; exit 1; fi
if [ ! -f .env ]; then echo 'Existing installation .env is required. Use the installer for a fresh installation.' >&2; exit 1; fi
COMPOSE=(docker compose -p pollydrop-public -f compose.yaml -f deploy/server/compose.server.yaml)
if [ "${1:-}" = '--frontend-from-source' ]; then
  COMPOSE+=(-f deploy/server/compose.frontend-source.yaml)
fi
./deploy/server/prepare-release.sh
"${COMPOSE[@]}" config --quiet
# Build all replacements before restarting the installed services.
"${COMPOSE[@]}" build
python3 deploy/server/migrate-installation.py
"${COMPOSE[@]}" up -d --no-build
# Bind-mounted configuration changes alone do not restart Caddy.
"${COMPOSE[@]}" exec -T caddy caddy reload --config /etc/caddy/Caddyfile
for attempt in $(seq 1 90); do
  if curl --fail --silent http://127.0.0.1:8088/api/health | python3 -c 'import sys,json; sys.exit(0 if json.load(sys.stdin).get("version")=="0.1.0-beta.4" else 1)' 2>/dev/null; then
    VERIFY=(python3 deploy/server/verify-interface.py --image pollydrop-public-frontend)
    if [ "${1:-}" = '--frontend-from-source' ]; then VERIFY+=(--source frontend/src/App.tsx); fi
    "${VERIFY[@]}"
    echo 'pollyDrop v0.1.0-beta.4 is ready. Existing secrets and data volumes were kept.'
    exit 0
  fi
  sleep 1
done
echo 'Update is not ready. Inspect logs for backend, frontend and caddy.' >&2
exit 1
