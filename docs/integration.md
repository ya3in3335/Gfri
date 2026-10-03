# Connecting Green Accesse to the existing Green Access Windows application

Green Accesse does not modify Green Access, its database, or its business logic. It needs one thing on the
PC: a **Green Access Bridge** that receives NFC events (protocol in `protocol.md`) and hands the UID to Green
Access the way Green Access already accepts card numbers.

```
Phone ──(encrypted WebSocket, LAN)──▶ Bridge ──▶ Green Access (unchanged) ──▶ its database / decisions / attendance
```

Choose **one** of the three integration methods below. Method A requires no change at all to Green Access.

---

## Step 0 – Install the reference Bridge on the Green Access PC

1. Install **Python 3.10+** for Windows from python.org (tick "Add python.exe to PATH").
2. Copy the folder `bridge/reference/` from this repository to the PC, e.g. `C:\GreenAccessBridge\`.
3. Open *Command Prompt* in that folder:
   ```bat
   py -m pip install -r requirements.txt
   ```
4. Allow the port in Windows Firewall (PowerShell as Administrator):
   ```powershell
   New-NetFirewallRule -DisplayName "Green Access Bridge" -Direction Inbound -Protocol TCP -LocalPort 8787 -Action Allow -Profile Private
   ```
5. Pair the phone:
   ```bat
   py green_access_bridge.py --pair --gym-name "My Gym"
   ```
   Enter the displayed code in Green Accesse (`pairing.md`). Compare the security codes. Leave the Bridge
   running; tap a card – the window prints `NFC scan UID=04A1B2C3D4E5 …`. The pipeline works end to end.

## Step 1 – Find out how Green Access expects the card number

Most desktop access-control applications accept cards from a **USB NFC/RFID reader that behaves like a
keyboard** (it "types" the card number followed by Enter). If Green Access works that way:

1. Open Green Access on the screen where you normally scan a card.
2. Scan a card with the existing USB reader **into Notepad** and note the number, e.g. `04A1B2C3D4E5`,
   `C3B2A104`, or `3283263748`.
3. Tap the **same card** on the phone and read the UID on the Green Accesse scanner screen.
4. Pick the matching `--uid-format`:

| Existing reader output | `--uid-format` |
|---|---|
| Same hex as the phone (`04A1B2C3`) | `hex` (default) |
| Hex with bytes reversed (`C3B2A104`) | `hex-reversed` |
| Decimal of the hex (`77705923`) | `decimal` |
| Decimal of the reversed bytes (`3283263748`) | `decimal-reversed` |

(If the existing reader outputs lowercase or adds separators, adjust `convert_uid()` in
`green_access_bridge.py` – a one-line change.)

## Method A – Keyboard wedge (no change to Green Access)

The Bridge types the UID + Enter into the focused window, exactly like the USB reader.

```bat
py green_access_bridge.py --output keyboard --uid-format hex --gym-name "My Gym"
```

* Green Access must be the **focused window** on its scan screen (as with a USB reader).
* Run the Bridge in the **logged-in user's session** (not as a Windows service – services cannot type into
  the desktop). Auto-start: *Task Scheduler → Create Task → Trigger "At log on" → Action*
  `pythonw.exe C:\GreenAccessBridge\green_access_bridge.py --output keyboard --uid-format hex`.
* Security: only validated hex digits and Enter can be typed; NFC data can never trigger shortcuts or
  commands.
* Limitation: the phone shows "Sent to Green Access", not the member decision, because a keyboard cannot
  read Green Access's answer.

## Method B – CSV file

If Green Access (or an existing script around it) can import or watch a file:

```bat
py green_access_bridge.py --output file --file C:\GreenAccess\nfc_scans.csv
```

Each scan appends `received_at,uid,device_id,scan_id,technologies`. `scan_id` is unique; retries are never
written twice.

## Method C – Native connector (recommended long-term, needs the Green Access developer)

Two options for the Green Access developer:

1. **Local HTTP endpoint inside Green Access** – the Bridge POSTs each scan to it:
   ```bat
   py green_access_bridge.py --output http --url http://127.0.0.1:8790/nfc
   ```
   Request body:
   ```json
   {"uid":"04A1B2C3D4E5","device_id":"GA-1D55F3EE499B5BA8","scan_id":"5f0c6a0e-…","timestamp":"2026-10-03T10:30:00Z"}
   ```
   Green Access runs its normal lookup/attendance logic and answers `200` with (optional):
   ```json
   {"success": true, "message": "Welcome, see you tomorrow", "result": "granted"}
   ```
   `result` ∈ `received | granted | denied | unknown_card | error`. The phone then shows
   **ACCESS GRANTED / ACCESS DENIED / UNKNOWN CARD** with the message – the decision stays in Green Access.
   Use `scan_id` to ignore duplicates. Only `127.0.0.1`/`localhost` URLs are accepted.

2. **Implement `protocol.md` directly in Green Access** (e.g. C#/.NET: `System.Net.WebSockets`,
   `ECDiffieHellman` P-256, `HMACSHA256`, `HKDF`, `AesGcm` – all built into .NET 6+). The Python reference
   (≈ 500 lines) and the Kotlin `TestBridge` are complete working models. Validate against the app with the
   interoperability test described in `building.md`.

## Checklist

- [ ] Bridge running on the Green Access PC, firewall rule on a **Private** network
- [ ] Phone paired, security codes match, dashboard shows **Connected**
- [ ] `--uid-format` matches the existing USB reader
- [ ] Test card: Green Access records the visit exactly once
- [ ] Phone at reception: Green Accesse open, **Keep screen on** enabled, charger connected
- [ ] PC sleep disabled; PC IP reserved in the router (or phone uses mDNS discovery)
