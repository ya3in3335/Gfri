# Architecture

## Product architecture

```
NFC card
   │  (13.56 MHz)
Android NFC hardware
   │  Reader Mode (android.nfc)
Green Accesse  ──────────────  READ NFC → SEND EVENT. No member data, no decisions.
   │  WebSocket, local Wi-Fi/LAN (or USB via adb reverse)
   │  ECDH pairing · HMAC mutual auth · AES-256-GCM per message
Green Access Bridge (Windows)
   │  keyboard wedge / file / local HTTP / native connector
Existing Green Access Windows application
   │
Existing Green Access database → member lookup · access decision · attendance
```

Green Accesse never sees member names, plans, payments or attendance. It sends a UID; Green Access decides.

## Android application layers

Single Gradle module (`:app`), package `com.greenaccesse.app`, organised by responsibility. Every layer
below `presentation` is plain Kotlin behind interfaces, so it is unit-tested on the JVM and can be
replaced without touching the rest of the app.

| Package | Responsibility | Key types |
|---|---|---|
| `nfc` | Reader Mode, tag → data, UID format, duplicate protection | `NfcReaderController`, `AndroidTagReader`, `TagParser`, `Uid`, `ScanDebouncer` |
| `protocol` | Wire messages, validation, handshakes, encryption | `Messages.kt`, `ProtocolCodec`, `HandshakeCrypto`, `PairingClient`, `SessionClient`, `SecureChannel` |
| `transport` | Moving text frames; pluggable | `Transport`, `TransportConnection`, `WebSocketTransport` (Wi-Fi), `UsbAdbTransport`, `TransportFactory` |
| `network` | Connection lifecycle, reconnection, heartbeat, discovery, connectivity | `ConnectionManager`, `ConnectionState`, `NetworkMonitor`, `BridgeDiscovery` |
| `pairing` | Pairing workflow and paired-PC storage | `PairingService`, `PairingRepository`, `PairedPc` |
| `auth` | Operator authentication abstraction | `AuthService`, `LocalAuthService`, `PasswordHasher` |
| `security` | Crypto primitives, Keystore-backed encryption | `Crypto`, `SecretCipher`, `KeystoreSecretCipher` |
| `storage` | Key/value persistence (plain and encrypted) | `KeyValueStore`, `SecureStore` |
| `history` | Local scan log (timestamp, UID, status, connection status) | `ScanHistoryRepository`, `EncryptedFileScanHistory` |
| `settings` | User preferences (DataStore) | `SettingsRepository`, `AppSettings` |
| `domain` | The scan pipeline | `ScanCoordinator` |
| `di` | Manual dependency injection | `AppContainer` |
| `presentation` | Jetpack Compose UI (Material 3), ViewModels | `MainActivity`, `AppRoot`, `screens/*` |

### Scan pipeline

```
ReaderCallback (binder thread)
  → AndroidTagReader.read(tag)            UID, tech list, cached NDEF (no authentication, no writes)
  → ScanCoordinator.onTagDiscovered       TagParser.parse → ScanDebouncer → history(PENDING)
  → ConnectionManager.sendScan            encrypted nfc_scan, await ack/nfc_response (5 s)
  → history(SENT | DELIVERED | REJECTED | FAILED_*), scanner UI, sound/vibration
```

### Connection state machine (`ConnectionManager`)

```
NotPaired ──pairing saved──▶ Connecting ──auth ok──▶ Connected
                                │  ▲                     │ link lost / heartbeat timeout
                     failure    ▼  │ backoff / Wi-Fi back ▼
                             Reconnecting ◀────────────────┘
Connected ──user disconnect──▶ Disconnected        ──PC: not_paired/unpair──▶ AuthenticationFailed (no retry)
Connected ──app in background > 60 s──▶ Disconnected("Paused") ──foreground──▶ Connecting
```

### Extension points

* **New transport** (USB accessory, Bluetooth, TLS WebSocket): implement `Transport`; register in
  `TransportFactory` and `TransportKind`.
* **Real authentication backend** (Green Access server, REST, Firebase, Supabase): implement `AuthService`;
  swap it in `AppContainer`.
* **QR pairing**: new UI that calls `PairingService.pair(...)`; see `pairing.md`.
* **Offline queue**: `ScanCoordinator` currently records offline scans as failed and never replays them
  (avoids unexpected attendance). Every scan has a `scan_id` and the Bridge de-duplicates by it, so an
  opt-in queue can be added safely.

## Lifecycle decisions

* **NFC** works only while the activity is resumed (Android Reader Mode rule). The controller enables
  Reader Mode in `onResume` and always disables it in `onPause`; the NFC state receiver is registered and
  unregistered with it. No reader leaks.
* **Rotation / process death**: UI state lives in ViewModels/StateFlows; long-lived objects live in
  `AppContainer` (process scope). "Remember me" sessions are restored after process death.
* **Background**: no foreground service. Scanning is impossible in the background anyway, so the
  connection is closed 60 s after the app leaves the foreground and re-established when it returns
  (battery friendly, compliant with background-execution limits). For a front-desk kiosk, keep the app
  open with **Settings → Keep screen on**.
* **Network**: `NetworkMonitor` (ConnectivityManager callback, Wi-Fi/Ethernet) wakes the reconnect loop
  immediately when the network returns and drops dead links when it disappears.
* **Coroutines**: all background work runs in `AppContainer.appScope` (SupervisorJob) or `viewModelScope`;
  sockets are closed on every exit path of the connection loop.

## Windows side

The Android project is independent. The PC side needs a component that speaks `protocol.md`:

* `bridge/reference/` — a working Python reference Bridge (pairing, auth, encryption, de-duplication,
  mDNS advertising, keyboard-wedge/file/HTTP output). Usable today, and the template for a native
  Green Access connector.
* See `integration.md` for the exact integration steps.
