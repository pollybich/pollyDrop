![pollyDrop · deployment](../images/deployment-en.svg)

[Русский](../../deploy/server/README.md) · **English** · [← pollyDrop](README.md)

# Debian 13 · drop.pollybich.ru

Requires Docker Engine, available TCP ports 80/443 and a DNS record pointing `drop.pollybich.ru` to your server. Run as the server owner; no SSH credentials need to be shared.

## Fresh installation

```bash
apt-get update && apt-get install -y git
git clone https://github.com/pollybich/pollyDrop.git /opt/pollydrop
cd /opt/pollydrop
./deploy/server/install-debian.sh
```

Inspect existing directories first. The installer adds a missing official Compose release with SHA-256 verification, downloads checked `v0.1.0-beta.4` backend/frontend assets and packages them into containers. No host Java or Node.js compiler is needed.

`.env` is generated locally once; updates preserve the encryption key and database password. Generated builds stay in ignored `.runtime/server`. PostgreSQL/backend are private, frontend binds `127.0.0.1:8088`, and Caddy obtains/renews certificates for `drop.pollybich.ru` using HTTP/1.1 and HTTP/2.

## Connect an existing installation

Use a fresh directory when moving from a previous checkout, and preserve `.env`. Run from the current project directory; `/opt/pollydrop` must not already exist:

```bash
POLLY_SOURCE_DIR="$PWD"
git clone https://github.com/pollybich/pollyDrop.git /opt/pollydrop &&
install -m 600 "$POLLY_SOURCE_DIR/.env" /opt/pollydrop/.env &&
cd /opt/pollydrop &&
./deploy/server/update-debian.sh
```

Do not overwrite an existing `/opt/pollydrop`; inspect its installation first. Point `drop.pollybich.ru` DNS to the server before migration. Subsequent updates use the regular commands below.

## Update

From your current checkout:

```bash
git pull --ff-only
./deploy/server/update-debian.sh
```

Verified assets and replacement images are prepared first. If port 8088 belongs to a previous installation of this service, its keys are checked against `.env` before stopping it. The script cold-copies database, encrypted content and certificates into `pollydrop-public` volumes, then renames the copied database/role to `pollydrop`. The key, password, UUIDs, deadlines and download counts remain intact. Original volumes are retained for recovery. Failed copy/database migration restarts original containers.

The new project starts, Caddy reloads, and verification checks both backend version and actual served JavaScript. A healthy backend alone cannot report a successful interface update. The form defaults to **1 hour**, with a **2-day** maximum.

Migration briefly stops the service. Existing links contain the previous domain: use the same `/s/...` path on the new domain while the deadline and limit remain valid. No automatic old-domain redirect is configured. Original volumes are backups with administrator-controlled retention.

`--frontend-from-source` explicitly compiles the checkout’s frontend; normal updates use release assets.

## Verify and troubleshoot

```bash
curl --fail https://drop.pollybich.ru/api/health
docker compose -p pollydrop-public -f compose.yaml -f deploy/server/compose.server.yaml logs --tail=50 backend caddy
```

Expect `status: ok` and `version: 0.1.0-beta.4`. Check the form, RU / EN switch and a test transfer. Refresh only the interface of an already migrated installation:

```bash
./deploy/server/update-interface.sh
```

The script replaces only frontend and verifies served JavaScript against the image.

## Data and limits

`pollydrop-public` owns separate database, content and certificate volumes. `down` preserves them; do not add `-v`. Restore requires database, content and the original `APP_MASTER_KEY` together. Revoke restored transfers before exposing an outdated backup to avoid repeating consumed deliveries. Automatic backups are not configured.

Nginx limits creation to 12 transfers/minute with a small burst and caps in-flight creation/delivery at two. Overload returns 429. Secret URL logs are disabled. These are initial beta limits, not comprehensive abuse protection.

## Country-based language

A bundled local DB-IP Lite country database selects Russian for `RU`, English elsewhere or for unknown addresses. IPv4/IPv6 are supported. Caddy sanitizes forwarding headers; Nginx passes the client address to the private backend. This mechanism sends no visitor IP to an external GeoIP service and stores no IP in the database/logs. Manual browser preference takes priority. VPN/proxy routing can affect the detected country.
