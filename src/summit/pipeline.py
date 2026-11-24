"""`summit update`: scan → analyze → layout → label in one go, and the server-side job that runs it from the app.

The job is a subprocess, not a thread: analysis loads CLAP on the GPU and starts its own process pool, which should
neither share the server's memory nor die with a UI request. Every step is incremental, so stopping is safe.
"""

import atexit
import os
import re
import signal
import subprocess
import sys
import threading
import urllib.request
from collections import deque

from . import db
from .config import ROOT


def update(log=print) -> None:
    from .analyze import analyze
    from .label import label
    from .layout import layout
    from .scan import counts, scan

    log("== scan")
    scan(log=log)
    log("== analyze")
    analyze(log=log)
    if counts(db.connect())["unplaced"]:
        log("== layout")
        layout(log=log)
        log("== label")
        label(log=log)
    else:
        log("the map is already up to date")
    tags(limit=TAG_LOOKUPS_PER_UPDATE, log=log)
    log("== done")


TAG_LOOKUPS_PER_UPDATE = 200  # per source: new tracks; a bigger backlog is Tools › Tags › Look up tags


def tags(limit: int | None = None, log=print) -> None:
    """Known-data tagging: Discogs and Last.fm lookups for tracks not looked up yet, then resolve (known.py)."""
    from .known import fetch, resolve

    for src in ("discogs", "lastfm"):
        log(f"== {src}")
        fetch([src], limit=limit, log=log)
    log("== resolve")
    resolve(log=log)


def notify_server() -> None:
    """Tell a running server to reload, so a terminal run shows up without restarting it. The server's own job reloads
    when it finishes, so its subprocess skips this."""
    if os.environ.get("SUMMIT_JOB"):
        return
    from .app import PORT

    try:
        urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{PORT}/api/reload", method="POST"), timeout=120)
        print("server reloaded")
    except OSError:
        pass  # not running: it loads everything when it starts


# Progress lines the steps print: "  scanned 500/3900", "  96/400 · 2.1 tracks/s …", and "analyze: 400 tracks · …" (the total).
PROGRESS = re.compile(r"^\s+(?:scanned )?(\d+)/(\d+)")
TOTAL = re.compile(r"^(?:analyze|transcode): (\d+) tracks")


class Job:
    """A `summit <args>` subprocess with its progress parsed from its output (`== stage` lines, `  n/total`)."""

    def __init__(self, on_done, args=("update",), first_stage="scan"):
        self.on_done, self.args, self.first, self.proc, self.lines = on_done, list(args), first_stage, None, deque(maxlen=200)
        self.stage, self.progress, self.code, self.stopped = None, None, None, False
        atexit.register(self.stop)  # closing the app ends the run; the next one resumes where it stopped

    @property
    def running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def start(self) -> bool:
        if self.running:
            return False
        self.lines.clear()
        self.stage, self.progress, self.code, self.stopped = self.first, None, None, False
        self.proc = subprocess.Popen(
            [sys.executable, "-u", "-m", "summit", *self.args], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, start_new_session=True,  # own process group, so stop() also reaches analyze's workers
            env={**os.environ, "SUMMIT_JOB": "1"},
        )
        threading.Thread(target=self._read, args=(self.proc,), daemon=True).start()
        return True

    def stop(self) -> None:
        if self.running:
            self.stopped = True
            os.killpg(self.proc.pid, signal.SIGTERM)

    def _read(self, proc: subprocess.Popen) -> None:
        for line in proc.stdout:
            line = line.rstrip()
            if not line:
                continue
            self.lines.append(line)
            if line.startswith("== "):
                self.stage, self.progress = line[3:].split(":")[0], None
            elif m := PROGRESS.match(line):
                self.progress = [int(m[1]), int(m[2])]
            elif m := TOTAL.match(line):
                self.progress = [0, int(m[1])]
        self.code = proc.wait()
        if self.code == 0:
            self.on_done()

    def state(self) -> dict:
        return {"running": self.running, "stage": self.stage, "progress": self.progress, "code": self.code,
                "stopped": self.stopped, "log": list(self.lines)[-8:]}
