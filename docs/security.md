# Security

## Threat model

| Threat | Mitigation |
|---|---|
| Random PC on the LAN pretends to be Green Access | Phone requires the paired `pc_id` **and** a valid HMAC server proof derived from the device secret (protocol §5). |
| Random phone sends fake scans | PC accepts only paired `device_id`s whose client proof is valid. |
| Eavesdropping on Wi-Fi (UIDs, secrets) | Pairing uses ECDH; all session traffic is AES-256-GCM with per-direction keys. The pairing code and device secret never travel. |
| Replay / reordering / reflection of frames | Strict per-direction sequence numbers in the GCM AAD, plus session id and direction. |
| Brute-forcing the pairing code | 6 digits, 5 attempts per code, 5-minute expiry, CSPRNG on the PC. |
| Man-in-the-middle during pairing | Matching security code (`XXXX-XXXX`) displayed on both sides; pair on a private network. |
| Malicious NFC tag (scripts, URLs, commands) | Tag data is parsed into bounded, control-character-free text; nothing is executed, opened or interpreted. Only the hex UID is used. The Bridge validates the UID with `^[0-9A-F]{2,32}$` before use; the keyboard sink can therefore only type hex digits + Enter. |
| Malformed or oversized network messages | 64 KiB frame limit, strict JSON parsing, field validation, unknown types rejected, binary frames rejected. |
| Stolen / copied phone storage | Secrets (device secret, account hash, session token, scan history) encrypted with an AES-256-GCM key held in the Android Keystore; backups and device-to-device transfer disabled. |
| Password guessing on the phone | PBKDF2-HMAC-SHA256 (120 000 iterations, random salt), exponential lockout after 5 failures. |
| Duplicate attendance | Debounce on the phone; `scan_id` de-duplication on the Bridge; no automatic replay of offline scans. |

## Data stored on the phone

| Data | Where | Protection |
|---|---|---|
| Operator username + PBKDF2 hash | SharedPreferences `green_accesse_secure` | Keystore AES-GCM |
| "Remember me" session token | same | Keystore AES-GCM |
| Paired PC id/name, device secret | same | Keystore AES-GCM |
| Scan history (time, UID, status, connection status) – max 1000 | `files/scan_history.bin` | Keystore AES-GCM |
| Settings, random device id | DataStore / SharedPreferences | app-private storage |

No member information, no passwords in plaintext, nothing sent to the Internet.

## Permissions

| Permission | Why |
|---|---|
| `NFC` | read cards |
| `INTERNET` | WebSocket to the PC on the local network (Android requires this permission for any socket) |
| `ACCESS_NETWORK_STATE` | detect Wi-Fi loss/return for automatic reconnection |
| `VIBRATE` | optional scan feedback |

Not requested: location, camera, contacts, SMS, call logs, storage, notifications, foreground service.
Network discovery uses `NsdManager`, which does not need location permission.

## Cleartext WebSocket

`network_security_config.xml` permits cleartext because gym PCs have no publicly trusted TLS certificate.
This is safe here because confidentiality and integrity are enforced end-to-end by the protocol's own
encryption and authentication. The app connects to no other host.

## Signing secrets

No keystore, password or token is in the repository (`.gitignore` blocks `*.jks`, `*.keystore`,
`keystore.properties`, `.env`). Release signing reads GitHub Secrets at build time only and the decoded
keystore is deleted at the end of the job. See `building.md`.

## Reporting

Report vulnerabilities privately to the repository owner rather than in a public issue.
