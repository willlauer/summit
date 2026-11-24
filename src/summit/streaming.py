"""Phone streaming health: keeping the Mac awake while it serves, and whether Tailscale reaches the phone directly.

Keep awake: while on, the server holds a `caffeinate -i -s` tied to its own process, so the Mac doesn't idle-sleep
(and, on mains power, doesn't system-sleep) mid-song. Closing the lid on battery still sleeps it: macOS decides that.
The setting lives in data/streaming.json (Tools › Phone streaming).

Tailscale: a peer with a current address is reached directly; one without is relayed through a DERP server, which caps
throughput and adds latency (the usual cause of stalls on cellular). Fix: let the router open a port (UPnP / NAT-PMP),
or set up a Tailscale peer relay.
"""

import json
import os
import shutil
import subprocess

from .config import DATA

SETTINGS = DATA / "streaming.json"
TAILSCALE = shutil.which("tailscale") or next(
    (p for p in ("/Applications/Tailscale.app/Contents/MacOS/Tailscale", "/opt/homebrew/bin/tailscale", "/usr/local/bin/tailscale")
     if os.path.exists(p)), None)
_caffeinate: subprocess.Popen | None = None


def settings() -> dict:
    try:
        return {"keep_awake": True} | json.loads(SETTINGS.read_text())
    except (OSError, ValueError):
        return {"keep_awake": True}


def set_keep_awake(on: bool) -> dict:
    SETTINGS.parent.mkdir(parents=True, exist_ok=True)
    SETTINGS.write_text(json.dumps(settings() | {"keep_awake": bool(on)}))
    apply()
    return awake_state()


def apply() -> None:
    """Start or stop the caffeinate assertion to match the setting (called at server start and on change)."""
    global _caffeinate
    want = settings()["keep_awake"]
    running = _caffeinate is not None and _caffeinate.poll() is None
    if want and not running:
        try:
            _caffeinate = subprocess.Popen(["caffeinate", "-i", "-s", "-w", str(os.getpid())],
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            _caffeinate = None
    elif not want and running:
        _caffeinate.terminate()
        _caffeinate = None


def awake_state() -> dict:
    return {"keep_awake": settings()["keep_awake"], "active": _caffeinate is not None and _caffeinate.poll() is None}


def tailscale() -> dict:
    """This Mac's Tailscale peers that are phones or tablets: online, and direct or relayed (and through where)."""
    if not TAILSCALE:
        return {"installed": False, "peers": []}
    try:
        out = subprocess.run([TAILSCALE, "status", "--json"], capture_output=True, text=True, timeout=5,
                             env={**os.environ, "TAILSCALE_BE_CLI": "1"}).stdout  # the app's binary: CLI mode, not the GUI
        d = json.loads(out)
    except (OSError, ValueError, subprocess.TimeoutExpired) as e:
        return {"installed": True, "error": str(e), "peers": []}
    peers = []
    for p in (d.get("Peer") or {}).values():
        if (p.get("OS") or "").lower() not in ("android", "ios"):
            continue
        peers.append({"name": p.get("HostName"), "os": p.get("OS"), "online": bool(p.get("Online")),
                      "direct": bool(p.get("CurAddr")), "relay": p.get("Relay") or None, "last_seen": p.get("LastSeen")})
    return {"installed": True, "running": d.get("BackendState") == "Running", "peers": peers}
