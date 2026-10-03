# Building, testing and releasing

## Requirements

* JDK 17 or newer (CI uses Temurin 17)
* Android SDK with platform `android-36` and build-tools `35.0.0` (Android Studio installs these, or use
  `sdkmanager`)
* No Android Studio needed: everything runs through the Gradle wrapper.

| Component | Version |
|---|---|
| Gradle (wrapper) | 8.14.3 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| compileSdk / targetSdk | 36 (Android 16) |
| minSdk | 26 (Android 8.0) |
| Compose BOM | 2025.12.01 |

## Local build

```bash
# point Gradle at your SDK once (not committed):
echo "sdk.dir=/path/to/Android/Sdk" > local.properties     # or export ANDROID_HOME

./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # Android lint (fails the build on errors)
./gradlew assembleDebug          # → app/build/outputs/apk/debug/Green-Accesse-debug.apk
./gradlew assembleRelease        # → app/build/outputs/apk/release/Green-Accesse-release.apk
```

Windows: use `gradlew.bat` instead of `./gradlew`.

Debug builds use the application id `com.greenaccesse.app.debug`, so they can be installed next to the
release app.

## Tests

`app/src/test` contains 92 JVM unit tests covering:

| Area | Test class |
|---|---|
| UID normalization | `UidTest` |
| NFC data parsing, NDEF, sanitization | `TagParserTest` |
| Duplicate scan prevention | `ScanDebouncerTest` |
| JSON serialization, protocol messages, rejection of malformed input | `ProtocolCodecTest` |
| HKDF (RFC 5869 vector), ECDH, AES-GCM, transcripts | `CryptoTest` |
| Encryption, replay/reorder/reflection/tamper rejection | `SecureChannelTest` |
| Pairing (right/wrong code, closed window, impostor PC, unknown phone) | `PairingTest`, `PairingServiceTest` |
| Authentication, sessions, lockout, remember me, storage encryption | `LocalAuthServiceTest` |
| Connection states, reconnection, backoff, heartbeat, scan acks/timeouts, unpair | `ConnectionManagerTest` |
| Real localhost WebSocket transport, error handling, USB loopback | `WebSocketTransportTest` |
| End-to-end scan pipeline, offline behaviour, debounce | `ScanCoordinatorTest` |
| Encrypted history persistence | `EncryptedFileScanHistoryTest` |
| Interoperability with the Python reference Bridge | `ReferenceBridgeInteropTest` |

The tests use a protocol-accurate Bridge (`testing/TestBridge.kt`), not mocks of the protocol, so pairing,
authentication and encryption are exercised for real.

### Interoperability test

```bash
pip install -r bridge/reference/requirements.txt
python bridge/reference/green_access_bridge.py --host 127.0.0.1 --port 8799 --pair-code 583921 --no-mdns --devices /tmp/devices.json &
GA_INTEROP_BRIDGE=127.0.0.1:8799 ./gradlew testDebugUnitTest --tests '*ReferenceBridgeInteropTest*'
```

CI runs this automatically (job **Interoperability with reference Bridge**).

NFC hardware itself cannot be exercised by JVM tests. Hardware checklist before a release:
1. Tap a card on the Scanner screen → UID appears, PC logs the scan, phone shows **SENT ✓**.
2. Leave the card on the phone → only one scan is sent.
3. Turn NFC off → "NFC IS OFF" with a button to the settings.
4. Stop the Bridge → "Connection lost / Reconnecting…", scans show **PC DISCONNECTED**; restart → **Connected**.
5. Rotate the phone, send the app to background and back → no crash, reader still works.

## GitHub Actions

### `build-apk.yml` (every push and pull request)

1. checkout → JDK 17 → Android SDK → Gradle cache
2. `./gradlew testDebugUnitTest`
3. `./gradlew lintDebug`
4. `./gradlew assembleDebug`
5. uploads artifact **`Green-Accesse-debug`** containing `Green-Accesse-debug.apk` (+ SHA-256)
6. uploads test and lint reports (always, so failures can be inspected)
7. separate job runs the interoperability test against the Python reference Bridge

Download: GitHub → **Actions** → select the run → **Artifacts** → `Green-Accesse-debug`.

### `release-apk.yml` (tags `v*`)

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow validates the tag (`vMAJOR.MINOR.PATCH`), runs the tests, builds the minified release APK with
`versionName = 1.0.0` and `versionCode = MAJOR*10000 + MINOR*100 + PATCH`, verifies the signature, and
creates the GitHub Release **Green Accesse v1.0.0** with `Green-Accesse-v1.0.0.apk` and its `.sha256`.
Tags containing `-` (e.g. `v1.1.0-beta.1`) are published as pre-releases. It can also be started manually
(**Actions → Release APK → Run workflow**) for an existing tag.

## Release signing (GitHub Secrets)

Never commit a keystore or password. Create the key once on your computer:

```bash
keytool -genkeypair -v -keystore green-accesse-release.jks -alias greenaccesse \
        -keyalg RSA -keysize 4096 -validity 10000
```

Keep `green-accesse-release.jks` and its passwords in a safe place (password manager + offline backup).
**If you lose it you can never publish an update that installs over the existing app.**

Encode it as Base64:

```bash
# Linux / macOS
base64 -w0 green-accesse-release.jks > keystore.b64        # macOS: base64 -i green-accesse-release.jks -o keystore.b64
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("green-accesse-release.jks")) | Set-Content keystore.b64
```

In GitHub: **Repository → Settings → Secrets and variables → Actions → New repository secret**, create:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the full content of `keystore.b64` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `greenaccesse` (the alias used above) |
| `KEY_PASSWORD` | the key password (same as keystore password if you pressed Enter) |

Then delete `keystore.b64`. The next tag push produces a properly signed APK.

If the secrets are missing, the release workflow still succeeds but signs with a temporary debug key and
prints a warning in the log and in the release notes. Such APKs are for testing only (each build has a
different key, so they cannot update each other).

Local release signing (optional): create an untracked `keystore.properties` in the project root:

```properties
storeFile=/absolute/path/green-accesse-release.jks
storePassword=...
keyAlias=greenaccesse
keyPassword=...
```

or export `GA_KEYSTORE_FILE`, `GA_KEYSTORE_PASSWORD`, `GA_KEY_ALIAS`, `GA_KEY_PASSWORD`.

## Installing the APK

1. Download `Green-Accesse-debug.apk` (Actions artifact – unzip it first) or `Green-Accesse-vX.Y.Z.apk`
   (Releases page) on the phone.
2. Open it. Android asks to allow installs from this source (browser / file manager) – allow it once.
3. Tap **Install**, then **Open**.

With a computer: `adb install -r Green-Accesse-v1.0.0.apk`.

Optional integrity check: compare `sha256sum Green-Accesse-v1.0.0.apk` with the attached `.sha256`.
