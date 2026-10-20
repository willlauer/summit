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
    if not counts(db.connect())["unplaced"]:
        log("== done: the map is already up to date")
        return
    log("== layout")
    layout(log=log)
    log("== label")
    label(log=log)
    log("== done")


# Progress lines the steps print: "  scanned 500/3900", "  96/400 · 2.1 tracks/s …", and "analyze: 400 tracks · …" (the total).
PROGRESS = re.compile(r"^\s+(?:scanned )?(\d+)/(\d+)")
TOTAL = re.compile(r"^analyze: (\d+) tracks")


class Job:
    def __init__(self, on_done):
        self.on_done, self.proc, self.lines = on_done, None, deque(maxlen=200)
        self.stage, self.progress, self.code, self.stopped = None, None, None, False
        atexit.register(self.stop)  # closing the app ends the run; the next one resumes where it stopped

    @property
    def running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def start(self) -> bool:
        if self.running:
            return False
        self.lines.clear()
        self.stage, self.progress, self.code, self.stopped = "scan", None, None, False
        self.proc = subprocess.Popen(
            [sys.executable, "-u", "-m", "summit", "update"], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, start_new_session=True,  # own process group, so stop() also reaches analyze's workers
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
