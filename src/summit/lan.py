"""Wi-Fi sync: the phone and the JM21 reach this Mac directly on the same Wi-Fi, no Tailscale.

A second listener on the home network (0.0.0.0:LAN_PORT) serves the same app as the localhost server, but only the
parts a device needs (playlists, folders, likes, the library, audio, art; see ALLOW) and only to a device that paired
with the code shown in Settings › Wi-Fi sync. Pairing hands the device a token it sends with every request
(X-Summit-Token, or ?t= on a URL). The Mac advertises itself over Bonjour as _summit._tcp so devices find it.

Device sync (/api/sync), rekordbox, tagging, the UI and everything else stay localhost-only.
"""

import atexit
import json
import secrets
import socket
import subprocess
import threading
import time

from .config import ROOT

LAN_PORT = 8767
STATE = ROOT / "data" / "lan.json"
_lock = threading.Lock()
_fails = {"n": 0, "until": 0.0}
_bonjour = None
_server = None

# (method or "*", path prefix). A prefix ending in "/" matches below it; otherwise the path must match exactly.
ALLOW = [
    ("GET", "/api/version"), ("GET", "/api/library"), ("GET", "/stream/"), ("GET", "/art/"),
    ("*", "/api/playlists"), ("*", "/api/playlists/"), ("*", "/api/folders"), ("*", "/api/folders/"),
    ("POST", "/api/likes"), ("POST", "/api/prefetch"), ("GET", "/api/offline"), ("GET", "/api/neighbors"),
    ("POST", "/api/suggest"), ("GET", "/api/search"), ("GET", "/api/extplaylists"), ("GET", "/api/lan/hello"),
    ("GET", "/api/app/version"), ("GET", "/api/app/apk"), ("GET", "/api/app/profile"),
]
OPEN = {"/api/lan/pair", "/api/lan/hello"}  # no token needed


def load() -> dict:
    try:
        s = json.loads(STATE.read_text())
    except (OSError, ValueError):
        s = {}
    s.setdefault("enabled", True)
    s.setdefault("code", f"{secrets.randbelow(10 ** 6):06d}")
    s.setdefault("devices", {})  # token -> {name, paired, seen}
    if not STATE.exists():
        save(s)  # keep the first code
    return s


def save(s: dict) -> None:
    STATE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(s, indent=2))
    tmp.replace(STATE)


def mac_name() -> str:
    try:
        return subprocess.run(["scutil", "--get", "ComputerName"], capture_output=True, text=True, timeout=3).stdout.strip() or socket.gethostname()
    except Exception:
        return socket.gethostname()


def addresses() -> list[str]:
    """This Mac's Wi-Fi / Ethernet addresses (what a device on the same network uses)."""
    out = []
    for iface in ("en0", "en1", "en2", "en3", "en4", "en5", "en6", "en7", "en8"):
        try:
            ip = subprocess.run(["ipconfig", "getifaddr", iface], capture_output=True, text=True, timeout=2).stdout.strip()
        except Exception:
            ip = ""
        if ip and ip not in out:
            out.append(ip)
    return out


def info() -> dict:
    s = load()
    return {"enabled": s["enabled"], "code": s["code"], "port": LAN_PORT, "running": _server is not None and s["enabled"],
            "name": mac_name(), "addresses": addresses(), "bonjour": _bonjour is not None and _bonjour.poll() is None,
            "devices": [{"id": t[:8], "name": d.get("name", "device"), "paired": d.get("paired"), "seen": d.get("seen")}
                        for t, d in s["devices"].items()]}


def settings(enabled: bool | None = None, new_code: bool = False) -> dict:
    with _lock:
        s = load()
        if enabled is not None:
            s["enabled"] = bool(enabled)
        if new_code:
            s["code"] = f"{secrets.randbelow(10 ** 6):06d}"
        save(s)
    _advertise(s["enabled"])
    return info()


def forget(device_id: str) -> dict:
    with _lock:
        s = load()
        s["devices"] = {t: d for t, d in s["devices"].items() if t[:8] != device_id}
        save(s)
    return info()


def pair(code: str, name: str) -> str | None:
    """A token for a device that knows the code; None (and a short lockout after many misses) otherwise."""
    with _lock:
        if time.time() < _fails["until"]:
            return None
        s = load()
        if not s["enabled"] or not secrets.compare_digest(str(code).strip(), s["code"]):
            _fails["n"] += 1
            if _fails["n"] >= 8:
                _fails.update(n=0, until=time.time() + 300)
            return None
        _fails["n"] = 0
        token = secrets.token_urlsafe(24)
        s["devices"][token] = {"name": (name or "device")[:60], "paired": time.time(), "seen": time.time()}
        save(s)
        return token


_seen_at = {}


def _check(token: str) -> bool:
    s = load()
    if not s["enabled"] or token not in s["devices"]:
        return False
    if time.time() - _seen_at.get(token, 0) > 60:  # note "last seen" at most once a minute
        _seen_at[token] = time.time()
        with _lock:
            s = load()
            if token in s["devices"]:
                s["devices"][token]["seen"] = time.time()
                save(s)
    return True


def allowed(method: str, path: str) -> bool:
    for m, p in ALLOW:
        if m not in ("*", method) and not (m == "GET" and method == "HEAD"):
            continue
        if path == p or (p.endswith("/") and path.startswith(p)):
            return True
    return path in OPEN


class LanGate:
    """ASGI wrapper for the Wi-Fi listener: the allowlist and the token, then the normal app."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        path, method = scope["path"], scope["method"]
        token = dict(scope.get("headers") or []).get(b"x-summit-token", b"").decode()
        if not token:
            from urllib.parse import parse_qs
            token = (parse_qs(scope.get("query_string", b"").decode()).get("t") or [""])[0]
        if not allowed(method, path):
            return await _deny(send, 403, "not available over Wi-Fi")
        if path not in OPEN and not _check(token):
            return await _deny(send, 401, "pair this device in Settings › Wi-Fi sync on the Mac")
        if path not in OPEN and not load()["enabled"]:
            return await _deny(send, 403, "Wi-Fi sync is off on the Mac")
        scope = dict(scope, summit_lan=True)
        return await self.app(scope, receive, send)


async def _deny(send, code, msg):
    body = json.dumps({"detail": msg}).encode()
    await send({"type": "http.response.start", "status": code, "headers": [(b"content-type", b"application/json")]})
    await send({"type": "http.response.body", "body": body})


def _advertise(on: bool) -> None:
    """Bonjour (dns-sd ships with macOS): devices on the Wi-Fi find "Summit on <Mac>" as _summit._tcp."""
    global _bonjour
    if _bonjour is not None and _bonjour.poll() is None:
        if on:
            return
        _bonjour.terminate()
        _bonjour = None
        return
    if on:
        _bonjour = subprocess.Popen(["dns-sd", "-R", f"Summit on {mac_name()}", "_summit._tcp", "local", str(LAN_PORT), "v=1"],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def start(app) -> None:
    """The Wi-Fi listener, in a thread, for as long as the server runs."""
    global _server
    if _server is not None:
        return
    import uvicorn

    cfg = uvicorn.Config(LanGate(app), host="0.0.0.0", port=LAN_PORT, log_level="warning", lifespan="off")
    _server = uvicorn.Server(cfg)
    threading.Thread(target=_server.run, daemon=True, name="summit-lan").start()
    _advertise(load()["enabled"])
    atexit.register(lambda: _bonjour and _bonjour.terminate())
