"""`summit reload`: make the open Summit window(s) load the current UI code, and confirm they did. Agents run this
after changing anything under web/ (or `summit reload --server` after changing Python), see CLAUDE.md.

Windows poll /api/ui/rev every 2 s (index.html) and reload when it changes; the revision changes on every server
start and on POST /api/ui/reload."""

import json
import subprocess
import time
import urllib.request
from pathlib import Path

from .app import PORT

BASE = f"http://127.0.0.1:{PORT}"
APP = Path.home() / "Applications/Summit.app"


def _get(path: str, method: str = "GET") -> dict:
    with urllib.request.urlopen(urllib.request.Request(BASE + path, method=method), timeout=5) as r:
        return json.loads(r.read())


def _wait(cond, secs: float):
    end = time.time() + secs
    while time.time() < end:
        try:
            v = cond()
            if v:
                return v
        except OSError:
            pass
        time.sleep(0.5)
    return None


def reload(wait_restart: bool = False) -> str:
    if not _wait(lambda: _get("/api/ui/windows"), 180 if wait_restart else 5):
        return "server not answering (summit service restart)"
    rev = _get("/api/ui/windows")["rev"] if wait_restart else _get("/api/ui/reload", "POST")["rev"]
    if not _get("/api/ui/windows")["windows"] and not _wait(lambda: _get("/api/ui/windows")["windows"], 5):
        if not APP.exists():
            return "no Summit window open"
        subprocess.run(["open", str(APP)], check=False)
        ok = _wait(lambda: _get("/api/ui/windows")["windows"], 30)
        return "opened Summit (no window was open)" if ok else "opened Summit, but no window has checked in yet"
    done = _wait(lambda: (w := _get("/api/ui/windows")["windows"]) and all(h == rev for h in w.values()) and len(w), 20)
    return f"reloaded {done} window(s)" if done else f"windows did not pick up revision {rev}: {_get('/api/ui/windows')['windows']}"
