# Troubleshooting

## NFC

| Symptom | Cause / fix |
|---|---|
| **NFC NOT AVAILABLE** | The phone has no NFC hardware. Use another phone. |
| **NFC IS OFF** | Tap **Open NFC settings** and enable NFC. The app resumes automatically. |
| **READER DISABLED** | **Settings → NFC → NFC Reader Enabled**. |
| Nothing happens when tapping a card | Find the phone's NFC antenna (usually centre-back, near the camera on some models) and hold the card flat for one second. Phone cases with metal plates or magnets block NFC. The app must be open and unlocked. |
| **READ ERROR – card removed too quickly** | Hold the card still until the beep. |
| Card scanned once, then ignored | Duplicate protection: the same card is ignored while it is still detected and for the configured delay (default 1000 ms). Change it in **Settings → Duplicate Scan Delay**. |
| UID shown is different from Green Access's | Some cards (and some phones) use random UIDs for privacy; such cards cannot be identified by UID by any reader. For byte-order or decimal differences, set `--uid-format` on the Bridge (see `integration.md`). |
| MIFARE Classic listed without data | Many phones (non-NXP chipsets) do not support MifareClassic. The UID is still read and sent; protected sectors are never accessed. |

## Connection

| Symptom | Cause / fix |
|---|---|
| **Not paired** | Pair the phone (`pairing.md`). |
| **Pairing required** | The PC does not recognise this phone anymore (revoked, Bridge reinstalled). Pair again. |
| PC not listed during pairing | mDNS can be blocked by routers/guest Wi-Fi ("client isolation"). Type the PC's IPv4 address shown by the Bridge. |
| "Cannot reach Green Access at …" | Check that the Bridge is running, phone and PC are on the **same network** (not guest Wi-Fi), the IP is correct, and **Windows Firewall** allows inbound TCP 8787 (see below). |
| "The PC answered but is not a Green Access Bridge" | Another program uses that port, or wrong port. |
| "Incorrect pairing code" | Re-type the code; after 5 wrong attempts generate a new one on the PC. |
| "Pairing is not active on the PC" | Start the Bridge with `--pair` (or open pairing in Green Access). |
| **Connection lost / Reconnecting…** | Normal when Wi-Fi drops or the PC sleeps. It reconnects automatically (1 → 30 s backoff, immediately when Wi-Fi returns). Disable PC sleep on the reception PC. |
| **Disconnected – Paused while the app is in the background** | By design: the app disconnects 60 s after leaving the screen and reconnects when reopened. Keep it open at reception and enable **Keep screen on**. |
| Scan shows **Failed – no response** | The Bridge received nothing back from Green Access within 5 s. Check the Bridge window/log. |
| IP of the PC changes | Give the PC a DHCP reservation in the router, or re-select it in **Settings → Connection → Server Address**. |

### Windows Firewall rule (PowerShell as Administrator)

```powershell
New-NetFirewallRule -DisplayName "Green Access Bridge" -Direction Inbound -Protocol TCP -LocalPort 8787 -Action Allow -Profile Private
```

Make sure the Wi-Fi/Ethernet network is set to **Private** in Windows, not Public.

### Find the PC's IP address

`ipconfig` in a Command Prompt → "IPv4 Address" of the active adapter (e.g. `192.168.1.20`).
The reference Bridge also prints it at start-up.

## USB transport (optional)

Wi-Fi is the recommended transport. USB works through Android Debug Bridge port forwarding:

1. On the phone: enable **Developer options → USB debugging**.
2. On the PC: install Android platform-tools, connect the cable, accept the debugging prompt on the phone.
3. Run `adb reverse tcp:8787 tcp:8787` (repeat after each reconnect of the cable).
4. In Green Accesse choose **USB (ADB reverse)** when pairing / in **Settings → Transport**.

If the cable is unplugged the app shows **Connection lost** and reconnects once `adb reverse` is active again.

## Login

| Symptom | Fix |
|---|---|
| "Too many attempts" | Wait the indicated time (30 s, doubling after further failures). |
| Forgot password | **Forgot password → Reset phone**. This erases the local account, pairing and history; create a new account and pair again. Green Access data on the PC is not affected. |

## Build / CI

| Symptom | Fix |
|---|---|
| `SDK location not found` | Create `local.properties` with `sdk.dir=…` or set `ANDROID_HOME`. |
| `Could not GET … 429 Too Many Requests` | Temporary Maven Central rate limit; re-run the job. |
| Lint failure | Download the `Green-Accesse-reports` artifact and open `lint-results-debug.html`. |
| Test failure | Same artifact → `reports/tests/testDebugUnitTest/index.html`. |
| Release APK "signed with a temporary debug key" | Configure the four signing secrets (`building.md`). |
