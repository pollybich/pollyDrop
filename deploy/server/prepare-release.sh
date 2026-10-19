#!/usr/bin/env bash
# Downloads published builds; does not need a compiler or change running services.
set -euo pipefail
DEPLOY_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$DEPLOY_DIR/../.." && pwd)"
VERSION='v0.1.0-beta.4'
REPOSITORY="$(python3 - <<'PYCODE'
import re, subprocess
from urllib.parse import urlsplit
url=subprocess.check_output(['git','remote','get-url','origin'],text=True).strip()
repo=urlsplit(url).path.strip('/').removesuffix('.git') if '://' in url else url.split(':',1)[1].removesuffix('.git')
if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',repo): raise SystemExit('Unsupported repository remote')
print(repo)
PYCODE
)"
BASE_URL="https://github.com/$REPOSITORY/releases/download/$VERSION"
JAR='pollydrop-0.1.0-beta.4.jar'
FRONTEND='pollydrop-interface-0.1.0-beta.4.tar.gz'
for program in curl sha256sum tar python3; do
  if ! command -v "$program" >/dev/null 2>&1; then echo "Required program: $program" >&2; exit 1; fi
done
if [ ! -f "$ROOT/compose.yaml" ]; then echo 'Place deploy/server inside the pollyDrop checkout.' >&2; exit 1; fi
STAGING="$(mktemp -d)"
trap 'rm -rf -- "$STAGING"' EXIT
for asset in "$JAR" "$FRONTEND" SHA256SUMS.txt; do
  curl --fail --silent --show-error --location --retry 3 "$BASE_URL/$asset" --output "$STAGING/$asset"
done
# Verify exactly the downloaded application assets; never accept arbitrary manifest paths.
python3 - "$STAGING" "$JAR" "$FRONTEND" <<'PY'
import hashlib, sys
from pathlib import Path
root=Path(sys.argv[1]); manifest={}
for line in (root/'SHA256SUMS.txt').read_text().splitlines():
    digest, name=line.split(maxsplit=1)
    manifest[name.removeprefix('*')]=digest
for name in sys.argv[2:]:
    expected=manifest.get(name)
    actual=hashlib.sha256((root/name).read_bytes()).hexdigest()
    if not expected or actual!=expected: raise SystemExit('Checksum verification failed: '+name)
    print('SHA-256 verified: '+name)
PY
mkdir -p "$ROOT/.runtime/server/backend" "$ROOT/.runtime/server/frontend"
cp "$DEPLOY_DIR/runtime/backend/Dockerfile" "$ROOT/.runtime/server/backend/Dockerfile"
cp "$STAGING/$JAR" "$ROOT/.runtime/server/backend/app.jar"
cp "$DEPLOY_DIR/runtime/frontend/Dockerfile" "$ROOT/.runtime/server/frontend/Dockerfile"
cp "$DEPLOY_DIR/nginx.frontend.conf" "$ROOT/.runtime/server/frontend/nginx.conf"
mkdir "$STAGING/dist"
# Validate archive paths/types before extraction.
python3 - "$STAGING/$FRONTEND" <<'PY'
import sys,tarfile
from pathlib import PurePosixPath
with tarfile.open(sys.argv[1]) as archive:
    for item in archive:
        path=PurePosixPath(item.name)
        if path.is_absolute() or '..' in path.parts or not (item.isfile() or item.isdir()):
            raise SystemExit('Unexpected archive entry')
PY
tar --no-same-owner -xzf "$STAGING/$FRONTEND" -C "$STAGING/dist"
chmod -R a+rX "$STAGING/dist"
if [ ! -f "$STAGING/dist/index.html" ]; then echo 'Frontend archive has no index.html.' >&2; exit 1; fi
# Replace only this script's generated frontend output.
if [ -d "$ROOT/.runtime/server/frontend/dist" ]; then rm -rf -- "$ROOT/.runtime/server/frontend/dist"; fi
mv "$STAGING/dist" "$ROOT/.runtime/server/frontend/dist"
echo 'Release builds prepared. Create local .env with scripts/init-env.sh before starting Compose.'
