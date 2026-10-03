# Green Access Bridge – reference implementation

A small, complete implementation of the Green Access Bridge protocol v1 (`../../docs/protocol.md`) in Python.
It runs on the Windows PC next to the existing Green Access application and delivers NFC scans from paired
Green Accesse phones to Green Access. It does **not** contain any membership logic.

It is used:
* by gym owners to connect Green Accesse to Green Access today (see `../../docs/integration.md`);
* by CI, to prove the Android app interoperates with an independent implementation;
* as the specification-by-example for a future native connector inside Green Access.

## Install

```bat
py -m pip install -r requirements.txt
```

`zeroconf` (phone auto-discovery) and `pynput` (keyboard output) are optional.

## Run

```bat
py green_access_bridge.py --pair --gym-name "My Gym"        :: open a pairing window, console output
py green_access_bridge.py --output keyboard --uid-format hex  :: type UIDs into Green Access
py green_access_bridge.py --output file --file scans.csv
py green_access_bridge.py --output http --url http://127.0.0.1:8790/nfc
py green_access_bridge.py --list                             :: paired phones
py green_access_bridge.py --revoke GA-1D55F3EE499B5BA8       :: remove a phone
```

| Option | Default | Meaning |
|---|---|---|
| `--port` | 8787 | TCP port |
| `--pc-name` | computer name | name shown on the phone |
| `--gym-name` | (empty) | shown on the phone dashboard |
| `--devices` | `paired_devices.json` | paired phones and their secrets – protect this file (NTFS permissions) |
| `--pairing-ttl` | 300 | pairing code lifetime in seconds |
| `--uid-format` | `hex` | `hex`, `hex-reversed`, `decimal`, `decimal-reversed` |
| `--no-mdns` | off | do not advertise `_greenaccess._tcp` on the LAN |

## Guarantees

* Only phones paired with a code shown on this PC can connect; 5 attempts per code, 5-minute expiry.
* All session traffic is AES-256-GCM encrypted with strict sequence numbers.
* UIDs must match `^[0-9A-F]{2,32}$`; nothing from a card is ever executed.
* Each `scan_id` is delivered to Green Access at most once (retries get the cached response).
