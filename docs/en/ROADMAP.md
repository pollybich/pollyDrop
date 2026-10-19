![pollyDrop · roadmap](../images/roadmap-en.svg)

[Русский](../../ROADMAP.md) · **English** · [← pollyDrop](README.md)

# Roadmap

A plan without calendar dates. Checked items mean implemented and verified behavior. See [requirements](REQUIREMENTS.md) for the target first-version scope; the beta covers guest transfers.

## First beta — v0.1.0-beta.1

- [x] React + TypeScript, Java 21 + Spring Boot, PostgreSQL and Flyway.
- [x] Responsive frosted-glass interface, editorial typography, animations and reduced motion.
- [x] Guest file transfers up to 25 MiB and text up to 64 KiB.
- [x] Lifetime, download limit and password.
- [x] Unpredictable links, separate management token and revocation.
- [x] AES-256-GCM content, Argon2id passwords and hashed tokens.
- [x] Explicit downloads; viewing a page does not consume a download.
- [x] Atomic reservations, concurrency protection and retry after a detected interruption.
- [x] Unavailable-content cleanup and reservation recovery after restart.
- [x] Integration/browser checks, setup instructions and API documentation.
- [x] Full Docker build and startup verification.
- [x] Prerelease publication with source and build assets.

## First update — v0.1.0-beta.2

- [x] Native browser file downloads without Blob URLs.
- [x] Metadata retries for transient network errors and a manual retry button.
- [x] Keep already received content visible when a background refresh fails.
- [x] Delete content after the final download or revocation; restore expiry timers after restart.
- [x] Two-day maximum retention, including migration of older transfers.
- [x] Remove the arrow from the logo.
- [x] Disable HTTP/3 advertisements without an available UDP port.
- [x] Debian updates that preserve data and keys.

## Beta improvements

- [ ] Collect feedback on the interface and transfer flow.
- [ ] Check Safari and Firefox on physical devices.
- [ ] Improve mass-upload protection and global request concurrency limits.
- [ ] Persist password rate limits across restarts and configure trusted proxies.
- [ ] Run load tests and a security review.
- [ ] Prepare externally hosted operation with HTTPS, observability and backups.
- [ ] Define metadata retention and deletion.

## Accounts and management

- [ ] Registration, sign-in, sessions and sign-out.
- [ ] Dashboard with transfers, status and statistics.
- [ ] Transfer ownership and server-side ownership checks.
- [ ] Keep creation and receiving available without an account.

## Public and internal use

- [ ] Internal installation and administrator guide.
- [ ] Update, restore and key-rotation procedures.
- [ ] Public mode and privacy-aware advertising.
- [ ] No third-party advertising scripts on transfer or dashboard pages.
- [ ] Disable advertising for internal deployment.

## Further development

- [ ] Evaluate S3-compatible storage and multiple backends.
- [ ] Develop corporate administration in response to concrete needs.
- [ ] Consider end-to-end encryption as a separate architecture change.

New features are assessed against security and solo-maintainer capacity. Permanent storage and native apps are outside the current scope.

## v0.1.0-beta.3

- [x] Italic p logo and consistent visual style.
- [x] Russian / English interface and saved manual preference.
- [x] Local IPv4/IPv6 country lookup for initial language.
- [x] One-hour default in the released frontend.
- [x] Checked binary updates and migration preserving keys, files and counters.

## v0.1.0-beta.4

- [x] Encrypted chunk uploads with durable database progress.
- [x] Page/backend restart recovery with original-file SHA-256 verification.
- [x] Idempotent chunk and transfer-finalization acknowledgement.
- [x] HTTP Range downloads, stable ETag and one allowance per completed session.
- [x] Abandoned-upload cleanup at the original deadline, without extending retention.
