# Pairing Green Accesse with Green Access

Pairing links **one phone** to **one Green Access PC**. It is done once per phone. Only paired phones can
send scans, and the phone only talks to the PC it was paired with.

## Operator steps

1. **On the PC** – start the Green Access Bridge in pairing mode. With the reference Bridge:
   ```
   python green_access_bridge.py --pair
   ```
   It displays:
   ```
   PAIRING CODE

       583921

   Valid for 5 min
   ```
2. **On the phone** – log in to Green Accesse, open **Dashboard → Connection → Pair with Green Access PC**
   (or **Settings → Security → Pair**).
3. Choose the transport:
   * **Local Wi-Fi** (recommended): the phone searches the network and lists Green Access PCs. Tap yours.
     If nothing appears, type the PC's IPv4 address (shown in the Bridge window, e.g. `192.168.1.20`).
   * **USB (ADB reverse)**: see `troubleshooting.md`.
4. Check the port (default `8787`), type the 6-digit code, tap **Connect**.
5. The phone shows **Paired ✓**, the PC name and a **security code** such as `05F5-D3B1`. The PC prints
   the same code. If they match, nobody intercepted the pairing. If they differ, revoke the pairing and
   repeat on a trusted network.
6. The phone connects automatically. The dashboard shows **● Connected – Green Access PC**.

## What happens technically

* The phone and PC exchange fresh P-256 ECDH public keys and random nonces.
* Each side proves it knows the pairing code with an HMAC derived from the ECDH secret **and** the code.
  The code itself never travels on the network.
* Both sides derive the same 256-bit device secret. The phone stores it encrypted with an Android Keystore
  key; the PC stores it in its paired-devices list.
* Every later connection uses the device secret for mutual challenge/response authentication and for the
  per-session AES-256-GCM keys. See `protocol.md` §4–6.

## Re-pairing, revoking, replacing

| Situation | What to do |
|---|---|
| Phone shows **Pairing required** | The PC no longer knows this phone (revoked or Bridge data reset). Pair again. |
| Remove a phone (lost/stolen) | On the PC: `python green_access_bridge.py --revoke GA-XXXXXXXXXXXXXXXX` (IDs via `--list`). |
| Phone retired | Phone: **Settings → Security → Revoke Pairing** (the PC is notified if reachable). |
| New PC | Pair the phone with the new PC; this replaces the old pairing. |
| Forgot phone password | **Forgot password → Reset phone** erases account, pairing and history; pair again. |

## Limits and recommendations

* A 6-digit code has 1 000 000 possibilities. The Bridge allows only 5 attempts per code and the code
  expires after 5 minutes, so online guessing is not practical.
* A passive eavesdropper on the Wi-Fi learns nothing useful (ECDH).
* An *active* attacker already on the LAN at the exact moment of pairing could try a man-in-the-middle;
  the matching **security code** on both screens detects this. Pair on the gym's private network, not on
  a guest Wi-Fi.

## Future: QR-code pairing

The pairing service (`PairingService.pair(host, port, transport, code)`) is independent of how the values
are entered. A QR code shown by the Bridge can carry `host`, `port`, `pc_id` and, in protocol v2, a 128-bit
secret instead of the 6-digit code. Adding it requires a camera permission and a scanner screen only;
the handshake, storage and connection layers stay unchanged.
