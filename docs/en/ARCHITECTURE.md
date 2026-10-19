![pollyDrop · architecture](../images/architecture-en.svg)

[Русский](../ARCHITECTURE.md) · **English** · [← pollyDrop](README.md)

# Beta architecture

Browser → Nginx → Spring Boot → PostgreSQL (metadata) + encrypted local files. Vite is the development proxy. One backend holds a dedicated PostgreSQL advisory lock; a second instance is rejected. Losing the lock connection makes the backend unready until restart. Multiple replicas are not supported.

## Transfers and secrets

`POST /api/transfers` accepts multipart `options` JSON and an optional `file` part:

```json
{"kind":"TEXT","text":"Hello","ttlSeconds":3600,"maxDownloads":1}
```

For files use `kind: FILE`. Optional passwords are at most 128 characters. TTL is 600–172800 seconds; the download limit is 1–10. The current form defaults to one hour.

The 201 response includes `readToken`, `manageToken` and `transfer`. Legacy creation uses two random 32-byte base64url tokens. Resumable creation derives separate read/manage tokens from its random upload secret using domain-separated SHA-256. The database stores only secret hashes. Recipient `/s/{readToken}` and management `/manage/{manageToken}` links are independent secrets. Protected filenames remain hidden before authorization.

Nginx disables URL access/error logs. Fonts are local; no third-party analytics or ad scripts. Metadata and management responses use `Cache-Control: no-store`.

| Endpoint | Behavior |
| --- | --- |
| `GET /api/health` | Readiness and version. |
| `GET /api/transfers/{token}` | Public conditions and state, without content. |
| `POST /api/transfers/{token}/download` | Explicit binary/text delivery; body `{}` or a password object. |
| `POST /api/transfers/{token}/download-ticket` | Authorize without spending a download; return relative download/status URLs. |
| `GET /api/downloads/{secret}` | Native attachment with single HTTP Range, If-Range/ETag and Accept-Ranges. Start within 60 seconds; a started session lasts until the original transfer deadline. PostgreSQL stores the secret hash. No password in the URL. |
| `GET /api/downloads/{secret}/status` | READY/SENDING/PARTIAL/SENT/FAILED/EXPIRED for this delivery. No content or download consumption. |
| `GET /api/manage/{token}` | Sender’s transfer status. |
| `DELETE /api/manage/{token}` | Revoke access; 204. |

Sessions and contiguous sent offsets persist in PostgreSQL across restarts. There are at most 1024 active unfinished sessions. Unused sessions expire after their start window; started sessions expire at the transfer deadline. HEAD returns attachment headers without reserving or consuming a delivery.

Errors: 400 parameters, 401 password, 404 unknown link, 409 active delivery, 410 access ended, 413 size, 429 password attempts, 507 quota. No cross-origin API access is enabled.

## Resumable uploads

The file UI uses `PUT /api/uploads/{secret}` (name, size, SHA-256, TTL, limit, password), `GET` status, `PATCH` with `Upload-Offset` and 1 MiB chunks, `POST .../complete`, and `DELETE` cancellation. The legacy multipart API remains compatible and is still used for text. The file limit remains 25 MiB.

Browser localStorage retains the random capability, filename, size, fingerprint and acknowledged offset, never file content or plaintext passwords. After a page reload, reselect the original file; its SHA-256 must match. Clearing browser storage loses that recovery capability. If all chunks are acknowledged, finalization needs no file selection.

Each chunk has independent AES-GCM encryption. Duplicate chunk acknowledgements and completion retries are idempotent. Assembly verifies the full SHA-256. Backend restart retains staged chunks and offsets. Network retries are bounded, with a manual continuation afterwards.

The original deadline starts when the server session is created, including pauses. Encrypted staging parts are deleted after assembly or expiry, using timers plus one-second cleanup retries. Assembly temporarily needs space for both parts and the completed ciphertext. Saved upload settings are immutable; start a new upload to change them.

## Delivery and interruption

A transaction locks the transfer row, checks all conditions and records a unique reservation. Delivery happens outside that transaction. GCM integrity is verified before the first plaintext byte. Each 64 KiB block checks expiry, revocation and reservation ownership. A separate transaction releases the reservation and increments the count only after the complete send and flush. An IOException frees the slot without consuming a download. Reservation IDs fence old streams from completing a newer reservation.

A partial response does not spend the quota; the contiguous prefix is durable and the session spends one delivery after completion. The same capability can replace its own stalled stream only. An unacknowledged offset gap or stale If-Range falls back to a full 200 response. Invalid or multiple ranges return 416 without consuming a delivery. Retries are possible after a server-detected interruption. Continuation uses the browser download manager and depends on browser/webview support. It is possible only while the server copy remains available; a completed last delivery deletes that copy and cannot be resumed afterwards. A completed server send does not prove a saved file; received bytes cannot be recalled. Text uses a Blob in the browser; files use native downloads. Server-side GCM verification loads a bounded file into memory; files are capped at 25 MiB. Multiuser load testing remains planned.

At startup the single backend releases old reservations, removes orphaned files, cleans unavailable content and restores expiry timers. The final delivery or revocation deletes content after transaction commit and before request handling completes. Expiry also deletes during an active delivery; the next block check stops its stream. One-second fallback cleanup retries failed deletions. Metadata remains for management. Timer accuracy depends on a running process and available storage; startup removes expired files before API readiness.

## Storage

File format: version byte 1 + random 12-byte nonce + AES-256-GCM ciphertext/tag, with the UUID as AAD. Temporary writes move atomically; failed creation transactions remove their content file. Argon2id uses 64 MiB, three iterations, parallelism 1 and a 16-byte salt. Five bad passwords per transfer/direct-connection address trigger a 15-minute in-memory lockout.

Keys live separately in `.env`. Public access requires HTTPS at the external proxy. Key rotation, antivirus, scaling, backups and automatic metadata retention are future work.

## Language selection

`GET /api/locale` returns `country` and `language`, never the visitor IP. `RU` selects `ru`; other or unknown addresses select `en`. A local DB-IP Lite MMDB is bundled in the JAR; source/checksum are pinned in `config/geoip-source.json`. No external GeoIP request is made. Only private/local ingress can provide X-Real-IP; Caddy sanitizes forwarded headers and Nginx passes the normalized client address. Stored manual RU / EN selection takes precedence. Transfer contents and user filenames are never translated.
