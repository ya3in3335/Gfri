# Green Accesse

**NFC Companion for Green Access** — turns any NFC-enabled Android phone into a secure NFC card reader for
the existing **Green Access** Windows gym-management application.

[![Build APK](../../actions/workflows/build-apk.yml/badge.svg)](../../actions/workflows/build-apk.yml)

```
   NFC membership card
            │
            ▼
   Android NFC hardware
            │
            ▼
      Green Accesse            reads the UID · sends the event · nothing else
            │
            ▼   local Wi-Fi (encrypted, authenticated)
   Green Access Bridge
            │
            ▼
   Green Access PC             existing app · existing database · member lookup · access decision · attendance
```

Green Accesse is **independent** of Green Access. It does not replace it, copy its database, or make any
membership decision. It does exactly one job: **READ NFC → SEND EVENT**.

---

## Features

* **NFC reader** using the official Android Reader Mode API: NfcA, NfcB, NfcF, NfcV, IsoDep, MifareClassic
  (where the phone supports it), MifareUltralight, Ndef, NdefFormatable. Reads UID, technologies and NDEF
  records. Never cracks, authenticates to, or writes cards.
* **Duplicate scan protection** – 500 ms / 1000 ms (default) / 2000 ms / custom. A card resting on the
  phone is sent once.
* **Secure pairing** with a 6-digit code shown on the PC (ECDH P-256; the code never travels) and a
  security code to compare on both screens.
* **Encrypted, mutually authenticated connection** (HMAC challenge/response + AES-256-GCM per message,
  replay protection). Random PCs on the network cannot connect, and cannot impersonate Green Access.
* **Local network only** – WebSocket over Wi-Fi/LAN, automatic discovery (mDNS), no cloud, no Internet.
  Optional USB transport via `adb reverse`.
* **Automatic reconnection** with backoff, instant retry when Wi-Fi returns, heartbeat, connection test.
* **Login** with a local operator account (PBKDF2 hash in Android Keystore-encrypted storage, lockout,
  remember me), behind an `AuthService` abstraction ready for a Green Access/REST/Firebase/Supabase backend.
* **Dashboard**, **Scanner**, **Connection**, **Pairing**, **History**, **Settings**, **About** screens –
  Material 3, dark mode by default, light/system modes.
* **Local scan history** (time, UID, status, connection status only – no member data), encrypted, with
  *Clear History*.
* **Offline-safe** – if the PC is unreachable the scan is shown and logged as *PC disconnected*; nothing is
  queued or uploaded, so no unexpected attendance duplicates.
* **Reference Windows Bridge** (`bridge/reference/`) with keyboard-wedge, CSV and local-HTTP outputs, tested
  against the app in CI.

## Requirements

| | |
|---|---|
| Phone | Android 8.0 (API 26) or newer, with NFC |
| PC | Windows PC running Green Access + the Green Access Bridge, on the same local network |
| Network | Phone and PC on the same Wi-Fi/LAN (not a guest network with client isolation) |

## Installation

1. Download the APK:
   * **Release**: GitHub → *Releases* → `Green-Accesse-vX.Y.Z.apk`
   * **Latest build**: GitHub → *Actions* → *Build APK* → latest run → artifact `Green-Accesse-debug`
2. Open the APK on the phone, allow installation from that source, tap **Install**.
   (Or `adb install -r Green-Accesse-v1.0.0.apk`.)

Details: [docs/building.md](docs/building.md#installing-the-apk).

## Login

On first launch, create the **operator account** for this phone (username/e-mail and a password of at least
8 characters). It is stored only on the phone: the password is hashed with PBKDF2 and the record is
encrypted with an Android Keystore key. *Remember me* keeps the session for 30 days.
*Forgot password* resets the phone (account, pairing and local history) – Green Access data on the PC is
never affected.

## Pairing

1. PC: `py green_access_bridge.py --pair` → shows **PAIRING CODE 583921**.
2. Phone: *Dashboard → Connection → Pair with Green Access PC*, select the PC (or type its IP), enter the code,
   **Connect**.
3. Phone shows **Paired ✓** and a security code; the PC shows the same code.

Details: [docs/pairing.md](docs/pairing.md).

## NFC scanning

Open the **Scanner** tab (scanning also works on any screen while the app is open):

```
READY  –  Hold NFC card near the phone
CARD DETECTED  –  UID 04 A1 B2 C3 D4 E5  –  Sending...
SENT ✓                              (or ACCESS GRANTED / ACCESS DENIED when Green Access reports it)
PC DISCONNECTED  –  Unable to send scan.
```

The phone must be unlocked with Green Accesse in the foreground (Android only delivers NFC Reader Mode
events to the visible app). For a reception desk, enable *Settings → Keep screen on* and keep the phone on a
charger.

## Connection

The **Connection** screen shows PC name, IP address, port, pairing status, connection status and last
connection, with **Test Connection** (real encrypted round trip), **Reconnect**, **Disconnect** and **Connect**.
States: *Connected*, *Connecting…*, *Connection lost – Reconnecting…*, *Disconnected*, *Pairing required*.

## Connecting to the existing Green Access application

See **[docs/integration.md](docs/integration.md)** for exact steps. In short, run the Bridge on the Green
Access PC and choose how it hands the UID to Green Access:

| Method | Change to Green Access | Command |
|---|---|---|
| Keyboard wedge (like a USB reader) | none | `--output keyboard --uid-format hex` |
| CSV file | none if it can import/watch files | `--output file --file scans.csv` |
| Local HTTP / native connector | small endpoint in Green Access | `--output http --url http://127.0.0.1:8790/nfc` |

## Troubleshooting

See **[docs/troubleshooting.md](docs/troubleshooting.md)** (NFC off, PC not found, firewall, pairing errors,
reconnection, USB transport, login lockout, CI failures).

## Security

* Pairing: ECDH P-256 + HMAC proof of a 6-digit code (5 attempts, 5-minute expiry); MITM detectable by
  comparing security codes.
* Sessions: mutual HMAC challenge/response; per-direction AES-256-GCM keys; strict sequence numbers.
* All inputs validated; malformed messages rejected; 64 KiB frame limit.
* NFC content is treated as inert data – never executed, opened or interpreted.
* Secrets encrypted with the Android Keystore; backups disabled; no secrets in the repository.
* Permissions: `NFC`, `INTERNET` (local socket), `ACCESS_NETWORK_STATE`, `VIBRATE`. No location, camera,
  contacts, SMS or call logs.

Details: [docs/security.md](docs/security.md).

## Architecture

Kotlin, Jetpack Compose, Material 3, coroutines/Flow, OkHttp WebSocket, kotlinx.serialization, DataStore.
Clean separation into `nfc`, `protocol`, `transport`, `network`, `pairing`, `auth`, `security`, `storage`,
`history`, `settings`, `domain`, `presentation`. Details: [docs/architecture.md](docs/architecture.md).

```
app/src/main/java/com/greenaccesse/app/
├── auth/          AuthService, LocalAuthService, PasswordHasher
├── di/            AppContainer (manual DI), DeviceIdentityStore
├── domain/        ScanCoordinator (READ NFC → SEND EVENT pipeline)
├── history/       Encrypted local scan history
├── network/       ConnectionManager, ConnectionState, NetworkMonitor, BridgeDiscovery (mDNS)
├── nfc/           NfcReaderController (Reader Mode), AndroidTagReader, TagParser, Uid, ScanDebouncer
├── pairing/       PairingService, PairingRepository
├── presentation/  MainActivity, AppRoot, theme, components, screens (Login, Dashboard, Scanner,
│                  Connection, Pairing, History, Settings, About)
├── protocol/      Messages, ProtocolCodec, HandshakeCrypto, PairingClient, SessionClient, SecureChannel
├── security/      Crypto, SecretCipher, KeystoreSecretCipher
├── settings/      AppSettings, DataStoreSettingsRepository
├── storage/       KeyValueStore, SecureStore
└── transport/     Transport, WebSocketTransport (Wi-Fi), UsbAdbTransport, TransportFactory
```

## Protocol

JSON over WebSocket at `ws://<pc>:8787/green-access/v1`. Example scan (inside the encrypted envelope):

```json
{
  "protocol_version": 1,
  "type": "nfc_scan",
  "scan_id": "5f0c6a0e-6f43-4f0e-9a39-1f5e2b7c9d10",
  "device_id": "GA-1D55F3EE499B5BA8",
  "timestamp": "2026-10-03T10:30:00Z",
  "uid": "04A1B2C3D4E5",
  "uid_length": 6,
  "technologies": ["NfcA", "Ndef"]
}
```

Response: `{"type":"nfc_response","scan_id":"…","success":true,"message":"Scan received"}`.
Messages: `pair_*`, `connect`, `auth_challenge`, `auth`, `connected`, `secure`, `ping`, `pong`, `status`,
`nfc_scan`, `ack`, `nfc_response`, `disconnect`, `unpair`, `error`.
Full specification: [docs/protocol.md](docs/protocol.md).

## GitHub Actions

| Workflow | Trigger | Does |
|---|---|---|
| `build-apk.yml` | every push / PR | unit tests → lint → `assembleDebug` → artifact **Green-Accesse-debug** (`Green-Accesse-debug.apk`); interoperability test against the reference Bridge |
| `release-apk.yml` | tag `v*` | tests → signed, minified release → GitHub Release with `Green-Accesse-vX.Y.Z.apk` |

## APK build

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
# → app/build/outputs/apk/debug/Green-Accesse-debug.apk
```

No Android Studio required (JDK 17 + Android SDK). Details: [docs/building.md](docs/building.md).

## Release process

1. Configure the signing secrets once: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
   ([instructions](docs/building.md#release-signing-github-secrets)).
2. `git tag v1.0.0 && git push origin v1.0.0`
3. The release appears under *Releases* with `Green-Accesse-v1.0.0.apk` and its SHA-256.

Without secrets, the workflow still publishes an APK but signs it with a temporary debug key and says so in
the release notes (testing only).

## Limitations

* **Foreground only**: Android delivers NFC Reader Mode events only to the visible, unlocked app. Background
  scanning is not possible; the connection is closed 60 s after the app leaves the screen and restored when
  it returns. No foreground service is used (it would drain the battery with no benefit).
* **Random-UID cards**: some cards (e.g. many bank cards, some DESFire/phone wallets) present a random UID on
  every tap; they cannot be identified by UID by any reader.
* **MIFARE Classic**: phones without an NXP NFC controller cannot use the MifareClassic API; the UID is still
  read. Protected sectors are never accessed (by design).
* **Integration**: the Android app is complete, but the Windows side needs the Bridge. The reference Bridge
  integrates via keyboard wedge, CSV or local HTTP; a native in-app connector requires the Green Access
  developer (protocol fully specified).
* **Keyboard-wedge mode** cannot return Green Access's decision to the phone (it shows "Sent to Green Access").
* **Authentication** is a local operator account in v1 (no cloud). A Green Access backend can be plugged in
  through `AuthService`.
* **Pairing** with a 6-digit code: an active attacker on the LAN *during* pairing could attempt a MITM;
  comparing the security codes detects it. QR pairing with a long secret is planned for protocol v2.
* **Offline scans** are not queued/replayed (intentional, to avoid unexpected attendance entries).
* **Future Android versions** may introduce a runtime "local network" permission; the app will need to
  request it once that is enforced for its target SDK.
* Cannot be verified on real NFC hardware in CI; see the manual hardware checklist in
  [docs/building.md](docs/building.md#tests).

## License

Proprietary – see [LICENSE](LICENSE).
