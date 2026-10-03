#!/usr/bin/env python3
"""
Green Access Bridge - reference implementation (protocol v1).

Runs on the Windows PC next to the existing Green Access application. It accepts NFC scan events from paired
Green Accesse phones over the local network and hands each UID to Green Access through a pluggable "sink":

  console   print the scan (default, for testing)
  keyboard  type the UID + Enter into the focused window, exactly like a USB keyboard-wedge NFC reader
  file      append to a CSV file that Green Access (or a script) can watch
  http      POST JSON to a local HTTP endpoint (for a future native Green Access connector)

The Bridge never decides membership: it only delivers the UID. Green Access remains responsible for lookups,
access decisions and attendance.

Security notes:
 - Only phones paired with a 6-digit code shown here can connect (ECDH P-256 + HMAC, see docs/protocol.md).
 - All post-handshake traffic is AES-256-GCM encrypted with per-direction keys and strict sequence numbers.
 - NFC data is treated as untrusted DATA: UIDs are validated against ^[0-9A-F]{2,32}$ and never executed.

Usage:
  python green_access_bridge.py --pair                # show a pairing code and accept phones
  python green_access_bridge.py                       # normal operation
  python green_access_bridge.py --output keyboard --uid-format hex
  python green_access_bridge.py --list | --revoke GA-XXXXXXXXXXXXXXXX
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import csv
import datetime as dt
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import socket
import struct
import sys
import time
import urllib.request
import uuid
from collections import OrderedDict
from http import HTTPStatus
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

PROTOCOL_VERSION = 1
WS_PATH = "/green-access/v1"
DEFAULT_PORT = 8787
MAX_FRAME = 64 * 1024
NONCE_BYTES = 16
BRIDGE_VERSION = "1.0.0-reference"
UID_RE = re.compile(r"^[0-9A-F]{2,32}$")
ID_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,64}$")

log = logging.getLogger("bridge")


# ------------------------------------------------------------------------------------------- crypto helpers

def b64(b: bytes) -> str:
    return base64.b64encode(b).decode()


def unb64(s: str) -> bytes:
    return base64.b64decode(s, validate=True)


def lp(*fields: bytes) -> bytes:
    """Length-prefixed concatenation (4-byte big-endian length + bytes)."""
    return b"".join(struct.pack(">I", len(f)) + f for f in fields)


def hmac256(key: bytes, *parts: bytes) -> bytes:
    h = hmac.new(key, digestmod=hashlib.sha256)
    for p in parts:
        h.update(p)
    return h.digest()


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    prk = hmac256(salt or bytes(32), ikm)
    out, prev, counter = b"", b"", 1
    while len(out) < length:
        prev = hmac256(prk, prev, info, bytes([counter]))
        out += prev
        counter += 1
    return out[:length]


class ProtocolError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def require(cond: bool, reason: str, code: str = "malformed_message"):
    if not cond:
        raise ProtocolError(code, reason)


def text_ok(v, max_len: int, allow_empty: bool = False) -> bool:
    return isinstance(v, str) and (allow_empty or v.strip()) and len(v) <= max_len and not any(ord(c) < 32 or ord(c) == 127 for c in v)


def nonce_ok(v) -> bool:
    try:
        return isinstance(v, str) and len(unb64(v)) == NONCE_BYTES
    except Exception:
        return False


def mac_ok(v) -> bool:
    try:
        return isinstance(v, str) and len(unb64(v)) == 32
    except Exception:
        return False


def parse(text: str) -> dict:
    require(isinstance(text, str) and len(text.encode()) <= MAX_FRAME, "frame too large or not text")
    try:
        msg = json.loads(text)
    except json.JSONDecodeError:
        raise ProtocolError("malformed_message", "invalid JSON")
    require(isinstance(msg, dict) and isinstance(msg.get("type"), str), "missing type")
    return msg


# ------------------------------------------------------------------------------------------- storage

class DeviceStore:
    """Paired phones. On Windows, protect this file with NTFS permissions (only the Bridge's user account)."""

    def __init__(self, path: Path):
        self.path = path
        self.devices: dict[str, dict] = {}
        if path.exists():
            self.devices = json.loads(path.read_text(encoding="utf-8"))

    def save(self):
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.devices, indent=2), encoding="utf-8")
        os.replace(tmp, self.path)
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass

    def add(self, device_id: str, name: str, secret: bytes, verification: str):
        self.devices[device_id] = {
            "name": name, "secret": b64(secret), "verification_code": verification,
            "paired_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        }
        self.save()

    def secret(self, device_id: str) -> bytes | None:
        d = self.devices.get(device_id)
        return unb64(d["secret"]) if d else None

    def remove(self, device_id: str) -> bool:
        if self.devices.pop(device_id, None) is not None:
            self.save()
            return True
        return False


class PairingWindow:
    def __init__(self, code: str | None, ttl_s: int = 300, max_attempts: int = 5):
        self.code = code
        self.expires = time.monotonic() + ttl_s
        self.attempts_left = max_attempts

    def active(self) -> bool:
        return self.code is not None and time.monotonic() < self.expires and self.attempts_left > 0


# ------------------------------------------------------------------------------------------- sinks

def convert_uid(uid: str, fmt: str) -> str:
    raw = bytes.fromhex(uid)
    if fmt == "hex":
        return uid
    if fmt == "hex-reversed":
        return raw[::-1].hex().upper()
    if fmt == "decimal":
        return str(int.from_bytes(raw, "big"))
    if fmt == "decimal-reversed":
        return str(int.from_bytes(raw, "little"))
    raise ValueError(fmt)


class Sink:
    def deliver(self, uid: str, scan: dict) -> tuple[bool, str, str]:
        """Returns (success, message shown on the phone, result code)."""
        raise NotImplementedError


class ConsoleSink(Sink):
    def deliver(self, uid, scan):
        print(f"[{dt.datetime.now():%H:%M:%S}] NFC scan  UID={uid}  device={scan['device_id']}  techs={','.join(scan.get('technologies', []))}", flush=True)
        return True, "Scan received", "received"


class KeyboardSink(Sink):
    """Types the UID followed by Enter into the focused window (Green Access scan field)."""

    def __init__(self):
        from pynput.keyboard import Controller, Key  # optional dependency
        self.kb, self.enter = Controller(), Key.enter

    def deliver(self, uid, scan):
        # uid has been validated to contain only [0-9A-F]: no control keys or shortcuts can be injected.
        self.kb.type(uid)
        self.kb.press(self.enter)
        self.kb.release(self.enter)
        return True, "Sent to Green Access", "received"


class FileSink(Sink):
    def __init__(self, path: Path):
        self.path = path

    def deliver(self, uid, scan):
        new = not self.path.exists()
        with self.path.open("a", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            if new:
                w.writerow(["received_at", "uid", "device_id", "scan_id", "technologies"])
            w.writerow([dt.datetime.now(dt.timezone.utc).isoformat(), uid, scan["device_id"], scan["scan_id"], " ".join(scan.get("technologies", []))])
        return True, "Scan received", "received"


class HttpSink(Sink):
    def __init__(self, url: str):
        require(url.startswith("http://127.0.0.1") or url.startswith("http://localhost"), "HTTP sink must be local", "internal_error")
        self.url = url

    def deliver(self, uid, scan):
        body = json.dumps({"uid": uid, "device_id": scan["device_id"], "scan_id": scan["scan_id"], "timestamp": scan["timestamp"]}).encode()
        req = urllib.request.Request(self.url, data=body, headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=4) as r:
                ok = 200 <= r.status < 300
                body = r.read(4096)
        except Exception as e:  # noqa: BLE001
            return False, f"Green Access unreachable: {type(e).__name__}", "error"
        if not ok:
            return False, f"Green Access returned HTTP {r.status}", "error"
        # Optional JSON answer from Green Access: {"success": bool, "message": str, "result": "granted|denied|unknown_card|received"}
        try:
            answer = json.loads(body or b"{}")
        except ValueError:
            answer = {}
        if not isinstance(answer, dict):
            answer = {}
        message = answer.get("message") if text_ok(answer.get("message"), 200) else "Scan received"
        result = answer.get("result") if answer.get("result") in ("received", "granted", "denied", "unknown_card", "error") else "received"
        success = answer.get("success") if isinstance(answer.get("success"), bool) else True
        return success, message, result


# ------------------------------------------------------------------------------------------- secure channel

class SecureChannel:
    def __init__(self, session_id: str, send_key: bytes, recv_key: bytes):
        self.session_id = session_id
        self.send, self.recv = AESGCM(send_key), AESGCM(recv_key)
        self.send_seq = 0
        self.recv_seq = 0

    def seal(self, msg: dict) -> str:
        self.send_seq += 1
        nonce = secrets.token_bytes(12)
        aad = f"GA1|{self.session_id}|s2c|{self.send_seq}".encode()
        ct = self.send.encrypt(nonce, json.dumps(msg, separators=(",", ":")).encode(), aad)
        return json.dumps({"type": "secure", "seq": self.send_seq, "nonce": b64(nonce), "data": b64(ct)})

    def open(self, frame: dict) -> dict:
        require(frame.get("type") == "secure", "expected secure frame", "unexpected_message")
        seq = frame.get("seq")
        require(isinstance(seq, int) and seq == self.recv_seq + 1, "bad sequence number")
        try:
            nonce, data = unb64(frame["nonce"]), unb64(frame["data"])
            require(len(nonce) == 12, "bad nonce")
            aad = f"GA1|{self.session_id}|c2s|{seq}".encode()
            plain = self.recv.decrypt(nonce, data, aad)
        except ProtocolError:
            raise
        except Exception:
            raise ProtocolError("auth_failed", "frame authentication failed")
        inner = parse(plain.decode("utf-8"))
        require(inner["type"] != "secure", "nested secure frame")
        self.recv_seq = seq
        return inner


# ------------------------------------------------------------------------------------------- bridge

class Bridge:
    def __init__(self, args):
        self.args = args
        self.pc_id = args.pc_id
        self.pc_name = args.pc_name[:64]
        self.store = DeviceStore(Path(args.devices))
        self.pairing = PairingWindow(None)
        self.sink: Sink = self._make_sink()
        self.seen_scans: OrderedDict[str, dict] = OrderedDict()  # scan_id -> response (idempotency)

    def _make_sink(self) -> Sink:
        o = self.args.output
        if o == "keyboard":
            return KeyboardSink()
        if o == "file":
            return FileSink(Path(self.args.file))
        if o == "http":
            return HttpSink(self.args.url)
        return ConsoleSink()

    def open_pairing(self, code: str | None = None):
        code = code or f"{secrets.randbelow(1_000_000):06d}"
        self.pairing = PairingWindow(code, ttl_s=self.args.pairing_ttl)
        print("\n" + "=" * 40 + f"\n  PAIRING CODE\n\n      {code}\n\n  Valid for {self.args.pairing_ttl // 60} min\n" + "=" * 40 + "\n", flush=True)

    async def process_request(self, connection, request):
        if request.path != WS_PATH:
            return connection.respond(HTTPStatus.NOT_FOUND, "Not a Green Access endpoint\n")
        return None

    async def handler(self, ws):
        peer = ws.remote_address
        try:
            first = parse(await asyncio.wait_for(ws.recv(), 15))
            if first["type"] == "pair_request":
                await self.handle_pairing(ws, first)
            elif first["type"] == "connect":
                await self.handle_session(ws, first)
            else:
                raise ProtocolError("unexpected_message", "expected pair_request or connect")
        except ProtocolError as e:
            log.warning("protocol error from %s: %s", peer, e)
            await self.send_error(ws, e.code, str(e))
        except (ConnectionClosed, asyncio.TimeoutError):
            pass
        except Exception:  # noqa: BLE001
            log.exception("unexpected error")
            await self.send_error(ws, "internal_error", "internal error")

    async def send_error(self, ws, code: str, message: str):
        try:
            await ws.send(json.dumps({"type": "error", "code": code, "message": message[:200]}))
        except Exception:  # noqa: BLE001
            pass

    async def handle_pairing(self, ws, req: dict):
        require(req.get("protocol_version") == PROTOCOL_VERSION, "unsupported version", "unsupported_version")
        require(isinstance(req.get("device_id"), str) and ID_RE.match(req["device_id"]), "invalid device_id")
        require(text_ok(req.get("device_name"), 64), "invalid device_name")
        require(nonce_ok(req.get("client_nonce")), "invalid client_nonce")
        if not self.pairing.active():
            raise ProtocolError("pairing_not_active", "Pairing is not active on this PC")
        try:
            client_pub_der = unb64(req["client_public_key"])
            client_pub = serialization.load_der_public_key(client_pub_der)
            require(isinstance(client_pub, ec.EllipticCurvePublicKey) and client_pub.curve.name == "secp256r1", "bad key")
        except ProtocolError:
            raise
        except Exception:
            raise ProtocolError("malformed_message", "invalid client_public_key")

        priv = ec.generate_private_key(ec.SECP256R1())
        server_pub_der = priv.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        server_nonce = secrets.token_bytes(NONCE_BYTES)
        await ws.send(json.dumps({
            "type": "pair_challenge", "protocol_version": PROTOCOL_VERSION, "pc_id": self.pc_id, "pc_name": self.pc_name,
            "server_nonce": b64(server_nonce), "server_public_key": b64(server_pub_der),
        }))
        confirm = parse(await asyncio.wait_for(ws.recv(), 30))
        require(confirm["type"] == "pair_confirm" and mac_ok(confirm.get("client_proof")), "expected pair_confirm")

        shared = priv.exchange(ec.ECDH(), client_pub)
        transcript_hash = hashlib.sha256(lp(b"GA-PAIR-V1", req["device_id"].encode(), self.pc_id.encode(),
                                            unb64(req["client_nonce"]), server_nonce, client_pub_der, server_pub_der)).digest()
        ikm = shared + self.pairing.code.encode()
        confirm_key = hkdf(ikm, transcript_hash, b"GA-PAIR-CONFIRM-V1")
        if not hmac.compare_digest(unb64(confirm["client_proof"]), hmac256(confirm_key, b"GA-PAIR-CLIENT")):
            self.pairing.attempts_left -= 1
            code = "too_many_attempts" if self.pairing.attempts_left <= 0 else "invalid_pairing_code"
            log.warning("wrong pairing code from %s (%d attempts left)", ws.remote_address, self.pairing.attempts_left)
            await ws.send(json.dumps({"type": "pair_result", "success": False, "error_code": code, "message": "Invalid pairing code"}))
            return
        secret = hkdf(ikm, transcript_hash, b"GA-DEVICE-SECRET-V1")
        verification = hashlib.sha256(b"GA-VERIFY-V1" + transcript_hash).hexdigest()[:8].upper()
        verification = verification[:4] + "-" + verification[4:]
        self.store.add(req["device_id"], req["device_name"], secret, verification)
        self.pairing = PairingWindow(None)  # one phone per code
        await ws.send(json.dumps({"type": "pair_result", "success": True, "server_proof": b64(hmac256(confirm_key, b"GA-PAIR-SERVER"))}))
        print(f"Paired: {req['device_name']} ({req['device_id']})  security code {verification}", flush=True)

    async def handle_session(self, ws, req: dict):
        require(req.get("protocol_version") == PROTOCOL_VERSION, "unsupported version", "unsupported_version")
        device_id = req.get("device_id")
        require(isinstance(device_id, str) and ID_RE.match(device_id), "invalid device_id")
        require(nonce_ok(req.get("client_nonce")), "invalid client_nonce")
        secret = self.store.secret(device_id)
        if secret is None:
            raise ProtocolError("not_paired", "This phone is not paired with this PC")
        server_nonce = secrets.token_bytes(NONCE_BYTES)
        await ws.send(json.dumps({"type": "auth_challenge", "protocol_version": PROTOCOL_VERSION, "pc_id": self.pc_id, "server_nonce": b64(server_nonce)}))
        auth = parse(await asyncio.wait_for(ws.recv(), 15))
        require(auth["type"] == "auth" and mac_ok(auth.get("client_proof")), "expected auth")
        auth_hash = hashlib.sha256(lp(b"GA-AUTH-V1", device_id.encode(), self.pc_id.encode(), unb64(req["client_nonce"]), server_nonce)).digest()
        if not hmac.compare_digest(unb64(auth["client_proof"]), hmac256(secret, b"GA-AUTH-CLIENT", auth_hash)):
            raise ProtocolError("auth_failed", "Authentication failed")
        session_id = "S-" + uuid.uuid4().hex[:16]
        await ws.send(json.dumps({
            "type": "connected", "session_id": session_id, "pc_name": self.pc_name,
            "server_proof": b64(hmac256(secret, b"GA-AUTH-SERVER", auth_hash, session_id.encode())),
            "heartbeat_interval_s": 15,
        }))
        ch = SecureChannel(session_id, hkdf(secret, auth_hash, b"GA-SESSION-S2C-V1"), hkdf(secret, auth_hash, b"GA-SESSION-C2S-V1"))
        name = self.store.devices[device_id]["name"]
        print(f"Connected: {name} ({device_id})", flush=True)
        await ws.send(ch.seal({"type": "status", "role": "bridge", "pc_name": self.pc_name, "gym_name": self.args.gym_name,
                               "green_access_connected": None if self.args.output == "console" else True, "bridge_version": BRIDGE_VERSION}))
        try:
            async for text in ws:
                msg = ch.open(parse(text))
                t = msg["type"]
                if t == "ping":
                    await ws.send(ch.seal({"type": "pong", "id": str(msg.get("id"))[:64], "timestamp": dt.datetime.now(dt.timezone.utc).isoformat()}))
                elif t == "nfc_scan":
                    for reply in self.handle_scan(msg, device_id):
                        await ws.send(ch.seal(reply))
                elif t == "status":
                    pass
                elif t == "unpair":
                    self.store.remove(device_id)
                    print(f"Phone revoked its pairing: {name} ({device_id})", flush=True)
                    return
                elif t == "disconnect":
                    return
                else:
                    await ws.send(ch.seal({"type": "error", "code": "unexpected_message", "message": "Unexpected message"}))
        finally:
            print(f"Disconnected: {name} ({device_id})", flush=True)

    def handle_scan(self, msg: dict, session_device: str) -> list[dict]:
        scan_id = msg.get("scan_id")
        require(isinstance(scan_id, str) and ID_RE.match(scan_id), "invalid scan_id")
        require(msg.get("device_id") == session_device, "device_id mismatch")
        uid = msg.get("uid")
        if not (isinstance(uid, str) and UID_RE.match(uid) and len(uid) % 2 == 0):
            return [{"type": "error", "code": "malformed_message", "message": "Invalid UID", "ref_id": scan_id}]
        if scan_id in self.seen_scans:  # retry of an already-processed scan: never record attendance twice
            return [{"type": "ack", "ref_type": "nfc_scan", "ref_id": scan_id}, self.seen_scans[scan_id]]
        replies = [{"type": "ack", "ref_type": "nfc_scan", "ref_id": scan_id}]
        try:
            ok, message, result = self.sink.deliver(convert_uid(uid, self.args.uid_format), msg)
        except Exception as e:  # noqa: BLE001 - a failing sink must never crash the session
            log.exception("sink failed")
            ok, message, result = False, f"Bridge output failed: {type(e).__name__}", "error"
        response = {"type": "nfc_response", "scan_id": scan_id, "success": ok, "message": message[:200], "result": result}
        self.seen_scans[scan_id] = response
        while len(self.seen_scans) > 1000:
            self.seen_scans.popitem(last=False)
        replies.append(response)
        return replies


def advertise(port: int, pc_name: str, pc_id: str):
    try:
        from zeroconf import ServiceInfo, Zeroconf
    except ImportError:
        print("zeroconf not installed: phones must enter this PC's IP address manually.")
        return None
    ip = local_ip()
    info = ServiceInfo(
        "_greenaccess._tcp.local.", f"{pc_name[:40]}._greenaccess._tcp.local.",
        addresses=[socket.inet_aton(ip)], port=port,
        properties={"pc_name": pc_name, "pc_id": pc_id, "version": str(PROTOCOL_VERSION)},
    )
    zc = Zeroconf()
    zc.register_service(info)
    return zc


def local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent; selects the LAN interface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


async def main_async(args):
    bridge = Bridge(args)
    if args.pair or args.pair_code:
        bridge.open_pairing(args.pair_code)
    zc = None if args.no_mdns else advertise(args.port, bridge.pc_name, bridge.pc_id)
    async with serve(bridge.handler, args.host, args.port, process_request=bridge.process_request, max_size=MAX_FRAME, ping_interval=20):
        print(f"Green Access Bridge listening on ws://{local_ip()}:{args.port}{WS_PATH}  (PC '{bridge.pc_name}', id {bridge.pc_id})", flush=True)
        try:
            await asyncio.Future()
        finally:
            if zc:
                zc.close()


def default_pc_id() -> str:
    return "PC-" + hashlib.sha256(socket.gethostname().encode()).hexdigest()[:12].upper()


def main():
    p = argparse.ArgumentParser(description="Green Access Bridge (reference implementation)")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument("--pc-name", default=socket.gethostname())
    p.add_argument("--pc-id", default=default_pc_id())
    p.add_argument("--gym-name", default="")
    p.add_argument("--devices", default=str(Path(__file__).with_name("paired_devices.json")))
    p.add_argument("--pair", action="store_true", help="open a pairing window with a random 6-digit code")
    p.add_argument("--pair-code", help=argparse.SUPPRESS)  # fixed code, for automated interoperability tests only
    p.add_argument("--pairing-ttl", type=int, default=300)
    p.add_argument("--output", choices=["console", "keyboard", "file", "http"], default="console")
    p.add_argument("--file", default="nfc_scans.csv")
    p.add_argument("--url", default="http://127.0.0.1:8790/nfc")
    p.add_argument("--uid-format", choices=["hex", "hex-reversed", "decimal", "decimal-reversed"], default="hex")
    p.add_argument("--no-mdns", action="store_true")
    p.add_argument("--list", action="store_true", help="list paired phones")
    p.add_argument("--revoke", metavar="DEVICE_ID", help="remove a paired phone")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    if args.pair_code is not None and not re.fullmatch(r"\d{6}", args.pair_code):
        p.error("--pair-code must be 6 digits")
    store = DeviceStore(Path(args.devices))
    if args.list:
        for did, d in store.devices.items():
            print(f"{did}  {d['name']}  paired {d['paired_at']}  security code {d.get('verification_code', '-')}")
        return
    if args.revoke:
        print("Revoked." if store.remove(args.revoke) else "Unknown device.")
        return
    try:
        asyncio.run(main_async(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
