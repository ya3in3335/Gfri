# Green Access Bridge Protocol — version 1

This document is the normative specification of the protocol spoken between **Green Accesse** (Android phone,
the *client*) and the **Green Access Bridge** (Windows PC, the *server*). Any Bridge that implements this
document can receive NFC scans from Green Accesse. Two independent implementations exist and are tested
against each other in CI:

* Kotlin client: `app/src/main/java/com/greenaccesse/app/protocol/`
* Python reference Bridge: `bridge/reference/green_access_bridge.py`

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

---

## 1. Transport

| Item | Value |
|---|---|
| Protocol | WebSocket (RFC 6455), text frames only |
| URL | `ws://<pc-address>:<port>/green-access/v1` |
| Default port | `8787` (TCP) |
| Encoding | UTF-8 JSON object per frame |
| Max frame size | 65 536 bytes. Larger frames MUST be rejected. |
| Binary frames | MUST NOT be sent. A receiver MUST close the connection on a binary frame. |
| Discovery (optional) | DNS-SD / mDNS service type `_greenaccess._tcp`, TXT keys `pc_name`, `pc_id`, `version` |

The WebSocket itself is not TLS-protected (gym PCs have no publicly trusted certificate). Instead, all
security is provided at the application layer: pairing with ECDH + a 6-digit code, mutual authentication
with HMAC, and AES-256-GCM encryption of every post-handshake message (sections 4–6).

A server MUST answer HTTP `404` for any path other than `/green-access/v1`.

USB: the same protocol can be carried over USB with `adb reverse tcp:8787 tcp:8787`; the phone then
connects to `ws://127.0.0.1:8787/green-access/v1` (see `troubleshooting.md`).

## 2. Message envelope

Every frame is a JSON object with a string `type` field. Field names are `snake_case`.

* Receivers MUST ignore unknown fields (forward compatibility).
* Receivers MUST reject (and not act upon) frames that are not valid JSON, lack `type`, have an unknown
  `type`, have missing/invalid required fields, or contain control characters (U+0000–U+001F, U+007F) in
  text fields.
* Binary values (nonces, keys, proofs, ciphertext) are standard Base64 with padding.
* Timestamps are ISO-8601 UTC strings, e.g. `2026-10-03T10:30:00Z`.
* Identifiers (`device_id`, `pc_id`, `session_id`, `scan_id`) match `^[A-Za-z0-9_.:-]{1,64}$`.

`protocol_version` is `1`. A peer receiving another version MUST answer `error` with code
`unsupported_version` and close.

## 3. Message catalogue

| Type | Direction | Phase | Purpose |
|---|---|---|---|
| `pair_request` | phone → PC | pairing (clear) | start pairing |
| `pair_challenge` | PC → phone | pairing (clear) | PC identity + ephemeral key |
| `pair_confirm` | phone → PC | pairing (clear) | proof of pairing code |
| `pair_result` | PC → phone | pairing (clear) | success + PC proof, or error |
| `connect` | phone → PC | auth (clear) | start authenticated session |
| `auth_challenge` | PC → phone | auth (clear) | server nonce |
| `auth` | phone → PC | auth (clear) | client proof |
| `connected` | PC → phone | auth (clear) | session id + server proof |
| `secure` | both | session | encrypted envelope for everything below |
| `ping` / `pong` | both | session | heartbeat / round-trip test |
| `status` | both | session | peer information |
| `nfc_scan` | phone → PC | session | an NFC card was read |
| `ack` | PC → phone | session | receipt of a message (optional) |
| `nfc_response` | PC → phone | session | final result for a scan |
| `disconnect` | both | session | orderly close |
| `unpair` | both | session | the sender forgot this pairing |
| `error` | both | any | error report |

## 4. Pairing

Pairing creates a 256-bit **device secret** shared by one phone and one PC. The PC shows a 6-digit pairing
code; the operator types it into the phone. **The code is never transmitted.**

```
phone                                                     PC (pairing window open, code C)
  |-- pair_request {device_id, device_name, app_version,     |
  |                 client_nonce Nc, client_public_key Pc} ->|
  |<- pair_challenge {pc_id, pc_name, server_nonce Ns,       |
  |                   server_public_key Ps} -----------------|
  |-- pair_confirm {client_proof} -------------------------->|  PC verifies
  |<- pair_result {success:true, server_proof} --------------|  phone verifies
```

### 4.1 Messages

```json
{"type":"pair_request","protocol_version":1,"device_id":"GA-1D55F3EE499B5BA8","device_name":"Google Pixel 8",
 "app_version":"1.0.0","client_nonce":"<16 bytes b64>","client_public_key":"<P-256 SPKI DER b64>"}

{"type":"pair_challenge","protocol_version":1,"pc_id":"PC-5BCE98F73F3E","pc_name":"Front Desk PC",
 "server_nonce":"<16 bytes b64>","server_public_key":"<P-256 SPKI DER b64>"}

{"type":"pair_confirm","client_proof":"<32 bytes b64>"}

{"type":"pair_result","success":true,"server_proof":"<32 bytes b64>"}
{"type":"pair_result","success":false,"error_code":"invalid_pairing_code","message":"Invalid pairing code"}
```

### 4.2 Key derivation

`LP(x)` = 4-byte big-endian length of `x` followed by `x`. Strings are UTF-8. Public keys are the raw DER
bytes (after Base64 decoding). Nonces are the raw 16 bytes.

```
shared          = ECDH-P256(own_private, peer_public)                 (32-byte X coordinate)
transcript_hash = SHA256( LP("GA-PAIR-V1") | LP(device_id) | LP(pc_id) | LP(Nc) | LP(Ns) | LP(Pc_der) | LP(Ps_der) )
ikm             = shared | UTF8(pairing_code)
confirm_key     = HKDF-SHA256(ikm, salt = transcript_hash, info = "GA-PAIR-CONFIRM-V1", L = 32)
device_secret   = HKDF-SHA256(ikm, salt = transcript_hash, info = "GA-DEVICE-SECRET-V1", L = 32)
client_proof    = HMAC-SHA256(confirm_key, "GA-PAIR-CLIENT")
server_proof    = HMAC-SHA256(confirm_key, "GA-PAIR-SERVER")
security_code   = upper(hex(SHA256("GA-VERIFY-V1" | transcript_hash)))[0:8]  shown as "XXXX-XXXX"
```

### 4.3 Rules

* The PC MUST only accept `pair_request` while a pairing window is open (operator action). Otherwise it
  answers `error` `pairing_not_active`.
* The PC MUST generate the code with a CSPRNG, SHOULD expire it after 5 minutes, MUST allow at most
  5 wrong attempts per code (`too_many_attempts` afterwards) and SHOULD close the window after one success.
* Both sides MUST compare proofs in constant time. The phone MUST verify `server_proof` before storing
  anything.
* The PC stores `device_id → device_secret` (+ name). The phone stores `pc_id`, `pc_name`, `device_secret`
  (encrypted with the Android Keystore).
* Both sides SHOULD display `security_code`; equal codes prove no one intercepted the pairing.

## 5. Session authentication

Every connection after pairing starts with mutual authentication:

```
phone -> connect        {"type":"connect","protocol_version":1,"device_id":"GA-…","client_nonce":"<Nc>","app_version":"1.0.0"}
PC    -> auth_challenge {"type":"auth_challenge","protocol_version":1,"pc_id":"PC-…","server_nonce":"<Ns>"}
phone -> auth           {"type":"auth","client_proof":"<b64>"}
PC    -> connected      {"type":"connected","session_id":"S-…","pc_name":"Front Desk PC","server_proof":"<b64>","heartbeat_interval_s":15}
```

```
auth_hash    = SHA256( LP("GA-AUTH-V1") | LP(device_id) | LP(pc_id) | LP(Nc) | LP(Ns) )
client_proof = HMAC-SHA256(device_secret, "GA-AUTH-CLIENT" | auth_hash)
server_proof = HMAC-SHA256(device_secret, "GA-AUTH-SERVER" | auth_hash | UTF8(session_id))
key_c2s      = HKDF-SHA256(device_secret, salt = auth_hash, info = "GA-SESSION-C2S-V1", L = 32)
key_s2c      = HKDF-SHA256(device_secret, salt = auth_hash, info = "GA-SESSION-S2C-V1", L = 32)
```

* Unknown `device_id` → PC answers `error` `not_paired` and closes. The phone then shows
  "Pairing required" and stops retrying.
* Wrong proof → `error` `auth_failed`.
* The phone MUST reject the PC if `pc_id` differs from the paired one or `server_proof` is wrong.
  This prevents any other PC on the network from impersonating Green Access.
* `heartbeat_interval_s` is 5–300. The phone sends `ping` at this interval and drops the connection if no
  `pong` arrives within 10 s.

## 6. Secure envelope

After `connected`, **every** message in both directions is wrapped:

```json
{"type":"secure","seq":1,"nonce":"<12 bytes b64>","data":"<ciphertext+tag b64>"}
```

```
data = AES-256-GCM-Encrypt(key_dir, nonce, plaintext = UTF8(inner JSON), aad = UTF8("GA1|" + session_id + "|" + dir + "|" + seq))
dir  = "c2s" (phone → PC, key_c2s)  or  "s2c" (PC → phone, key_s2c)
```

* `seq` starts at 1 in each direction and increments by exactly 1. A receiver MUST reject any frame whose
  `seq` is not `last_seq + 1` (replay, reordering, deletion) and MUST close the connection.
* The 12-byte nonce MUST be random per frame. The GCM tag is 128 bits.
* Authentication failure or nested `secure` frames MUST close the connection.
* Plain (unencrypted) frames after `connected` MUST be rejected, except `error`.

## 7. Session messages (inside `secure`)

### `nfc_scan` (phone → PC)

```json
{
  "type": "nfc_scan",
  "protocol_version": 1,
  "scan_id": "5f0c6a0e-6f43-4f0e-9a39-1f5e2b7c9d10",
  "device_id": "GA-1D55F3EE499B5BA8",
  "timestamp": "2026-10-03T10:30:00Z",
  "uid": "04A1B2C3D4E5",
  "uid_length": 6,
  "technologies": ["NfcA", "Ndef"],
  "ndef": {
    "available": true,
    "type": "org.nfcforum.ndef.type2",
    "writable": false,
    "records": [{"tnf": 1, "record_type": "T", "payload_length": 14, "text": "Member card"}]
  }
}
```

* `uid`: uppercase hex, no separators, byte order as reported by Android, 1–16 bytes. It MUST match
  `^[0-9A-F]{2,32}$` and `uid_length * 2 == len(uid)`.
* `technologies`: short names among `NfcA, NfcB, NfcF, NfcV, IsoDep, MifareClassic, MifareUltralight,
  Ndef, NdefFormatable, NfcBarcode`.
* `ndef` is optional; at most 8 records; `text` is only present for NFC Forum Text and URI records,
  control-character-free and at most 256 characters.
* `scan_id` is unique per physical scan. **The Bridge MUST de-duplicate by `scan_id`**: if a `scan_id`
  is received again it MUST NOT record attendance again and SHOULD repeat the previous `nfc_response`.

**Security:** every field of `nfc_scan` is untrusted data read from a card. The Bridge MUST NOT execute,
open, evaluate or pass to a shell any of it. The `uid` is the only value Green Access needs.

### `ack` (PC → phone, optional)

```json
{"type":"ack","ref_type":"nfc_scan","ref_id":"5f0c6a0e-6f43-4f0e-9a39-1f5e2b7c9d10"}
```

Receipt only. The phone shows "Delivered" if an `ack` arrives but no `nfc_response` within 5 s.

### `nfc_response` (PC → phone)

```json
{"type":"nfc_response","scan_id":"5f0c6a0e-…","success":true,"message":"Scan received","result":"received"}
```

* MUST be sent within 5 s for each `nfc_scan`; otherwise the phone records "Failed – no response".
* `success`: the Bridge accepted and forwarded the scan to Green Access.
* `message`: ≤ 256 characters, displayed on the phone as plain text.
* `result` (optional, informational): `received`, `granted`, `denied`, `unknown_card`, `error`.
  The phone never makes membership decisions; it only displays what Green Access reports.

### `ping` / `pong`

```json
{"type":"ping","id":"7c1e…","timestamp":"2026-10-03T10:30:00Z"}
{"type":"pong","id":"7c1e…","timestamp":"2026-10-03T10:30:00.012Z"}
```

Either side MAY ping; the receiver MUST answer with the same `id`.

### `status`

```json
{"type":"status","role":"device","app_version":"1.0.0","nfc_enabled":true}
{"type":"status","role":"bridge","pc_name":"Front Desk PC","gym_name":"My Gym","green_access_connected":true,"bridge_version":"1.0.0"}
```

Both sides send `status` right after `connected`. The phone displays `gym_name` on its dashboard.
`green_access_connected` tells whether the Bridge can currently reach the Green Access application.

### `disconnect`, `unpair`, `error`

```json
{"type":"disconnect","reason":"client_disconnect"}
{"type":"unpair","reason":"Pairing revoked on phone"}
{"type":"error","code":"malformed_message","message":"Invalid UID","ref_id":"5f0c6a0e-…"}
```

* `unpair` from the phone: the PC MUST delete the device secret. From the PC: the phone deletes its pairing.
* `error` with `ref_id` = a `scan_id` completes that scan as failed.

### Error codes

| Code | Meaning |
|---|---|
| `malformed_message` | invalid JSON / fields / sizes |
| `unsupported_version` | protocol version not supported |
| `not_paired` | unknown device_id |
| `auth_failed` | proof or frame authentication failed |
| `invalid_pairing_code` | wrong pairing code |
| `pairing_not_active` | no pairing window open |
| `too_many_attempts` | pairing code locked |
| `unexpected_message` | message not valid in this state |
| `internal_error` | Bridge-side failure |

## 8. Timeouts and reconnection (phone behaviour)

| Item | Value |
|---|---|
| TCP/WebSocket connect timeout | 6 s |
| Handshake step timeout | 10 s (pairing: 20 s) |
| Scan response timeout | 5 s |
| Pong timeout | 10 s |
| Reconnect backoff | 1, 2, 4, 8, 16, 30, 30 … s (+ up to 20 % jitter); immediate retry when Wi-Fi returns |

## 9. Versioning

New optional fields may be added to version 1 at any time (receivers ignore unknown fields). Any change to
key derivation or required fields requires `protocol_version` 2. A planned v2 addition is QR-code pairing
carrying a 128-bit secret instead of a 6-digit code.
