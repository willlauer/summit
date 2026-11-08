"""Shared state for device sync: the config file (shared with the standalone MusicSync app, so the Garmin's
track index and settings carry over) and background jobs. One job at a time, since every job touches a device."""

import importlib
import json
import multiprocessing
import os
import queue
import threading
import time
import traceback

from . import rekordbox_sd as rb

APP_DIR = os.path.expanduser("~/Library/Application Support/MusicSync")
CACHE_DIR = os.path.expanduser("~/Library/Caches/MusicSync")
CONFIG_PATH = os.path.join(APP_DIR, "config.json")

DEFAULTS = {
    # None = auto: ~/Desktop/rekordbox.xml, then ~/Documents/rekordbox.xml
    "xml_path": None,
    "library_root": "~/Music/Music/Media.localized/Music",
    "fiio_mount": "/Volumes/Untitled",
    "fiio_android_root": "/storage/external_sd/Music",
    "garmin_playlists": [],
    "garmin_bitrate": "320k",
    # Garmin .m3u8 line format: "garmin" = what Garmin Express writes
    # (0:/MUSIC/A0001.MP3); anything else is a template where {file} is A0001.mp3.
    "garmin_playlist_line": "garmin",
}

_config_lock = threading.Lock()


def load_config():
    with _config_lock:
        cfg = dict(DEFAULTS)
        try:
            with open(CONFIG_PATH, encoding="utf-8") as fh:
                cfg.update(json.load(fh))
        except (OSError, ValueError):
            pass
        return cfg


def save_config(updates):
    with _config_lock:
        cfg = dict(DEFAULTS)
        try:
            with open(CONFIG_PATH, encoding="utf-8") as fh:
                cfg.update(json.load(fh))
        except (OSError, ValueError):
            pass
        cfg.update({k: v for k, v in updates.items() if k in DEFAULTS})
        write_json(CONFIG_PATH, cfg)
        return cfg


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, ensure_ascii=False)
    os.replace(tmp, path)


def read_json(path, default):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return default


def human(n):
    return rb.human(n)


# ─── Background jobs ───────────────────────────────────────────────────────

class Cancelled(Exception):
    pass


class Job:
    def __init__(self, kind, device, title):
        self.id = f"{int(time.time() * 1000)}"
        self.kind = kind
        self.device = device
        self.title = title
        self.status = "running"
        self.done = 0
        self.total = 0
        self.message = "Starting…"
        self.log = []
        self.result = None
        self.error = None
        self.cancel_requested = False
        self.started = time.time()
        self.finished = None

    def step(self, message, done=None, total=None):
        if self.cancel_requested:
            raise Cancelled()
        self.message = message
        if done is not None:
            self.done = done
        if total is not None:
            self.total = total

    def note(self, line):
        self.log.append(line)
        if len(self.log) > 2000:
            del self.log[:500]

    def to_dict(self):
        return {
            "id": self.id, "kind": self.kind, "device": self.device, "title": self.title,
            "status": self.status, "done": self.done, "total": self.total,
            "message": self.message, "log": self.log[-300:], "result": self.result,
            "error": self.error, "started": self.started, "finished": self.finished,
        }


_job = None
_job_lock = threading.Lock()


def current_job():
    return _job


def start_job(kind, device, title, fn):
    """Run fn(job) in a thread. One job at a time, since every job touches a device."""
    global _job
    with _job_lock:
        if _job is not None and _job.status == "running":
            raise RuntimeError(f"Busy: {_job.title}")
        job = Job(kind, device, title)
        _job = job

    def run():
        try:
            job.result = fn(job)
            job.status = "done"
            job.message = "Done"
        except Cancelled:
            job.status = "cancelled"
            job.message = "Cancelled"
        except SystemExit as e:
            job.status = "error"
            job.error = str(e.code)
        except Exception as e:  # surfaced in the UI
            job.status = "error"
            job.error = str(e) or e.__class__.__name__
            job.note(traceback.format_exc())
        finally:
            job.finished = time.time()

    threading.Thread(target=run, daemon=True).start()
    return job


# ─── Isolated jobs (anything that talks to the watch over USB) ─────────────
#
# Each run gets its own process. A wedged MTP session or a leaked USB claim
# then dies with that process instead of blocking every later attempt until
# the app restarts, and Cancel can stop a transfer mid-file.

STALL_SECONDS = 120


class _ProxyJob:
    """Stands in for Job inside the child; forwards progress to the parent.
    step() is also where a stop request lands, so the device code unwinds
    through its finally blocks and closes the MTP session cleanly — an
    abandoned session leaves the watch refusing connections until replugged."""

    def __init__(self, q, stop):
        self.q = q
        self.stop = stop

    def step(self, message, done=None, total=None):
        if self.stop.is_set():
            raise Cancelled()
        self.q.put(("step", message, done, total))

    def note(self, line):
        self.q.put(("note", line))


def _child(q, stop, module, func, args, parent_pid):
    import signal
    import time

    def on_term(*_):
        stop.set()
        raise KeyboardInterrupt  # unwinds through finally: closes the session
    signal.signal(signal.SIGTERM, on_term)

    def watch_parent():
        while os.getppid() == parent_pid:
            time.sleep(1)
        stop.set()           # app quit: finish the current step, close cleanly
        time.sleep(30)
        os._exit(0)          # last resort
    threading.Thread(target=watch_parent, daemon=True).start()
    try:
        result = getattr(importlib.import_module(module), func)(*args, _ProxyJob(q, stop))
        q.put(("result", result))
    except (Cancelled, KeyboardInterrupt):
        q.put(("cancelled",))
    except SystemExit as e:
        q.put(("error", str(e.code), ""))
    except Exception as e:
        q.put(("error", str(e) or e.__class__.__name__, traceback.format_exc()))


def start_isolated_job(kind, device, title, module, func, args=()):
    """Like start_job, but runs module.func(*args, job) in a child process."""
    def run(job):
        import time
        ctx = multiprocessing.get_context("spawn")
        q = ctx.Queue()
        stop = ctx.Event()
        proc = ctx.Process(target=_child, args=(q, stop, module, func, tuple(args), os.getpid()), daemon=True)
        proc.start()
        last = time.time()
        stopping = None  # (deadline, exception to raise once stopped)

        def request_stop(exc):
            nonlocal stopping
            if stopping is None:
                stop.set()
                job.message = "Stopping — closing the connection cleanly…"
                stopping = (time.time() + 15, exc)

        try:
            while True:
                if job.cancel_requested:
                    request_stop(Cancelled())
                if stopping and time.time() > stopping[0]:
                    raise stopping[1]  # finally escalates to SIGTERM, then kill
                try:
                    msg = q.get(timeout=0.5)
                except queue.Empty:
                    if not proc.is_alive():
                        if stopping:
                            raise stopping[1]
                        raise RuntimeError(f"The device worker exited unexpectedly (code {proc.exitcode}).")
                    if time.time() - last > STALL_SECONDS:
                        request_stop(RuntimeError("The watch stopped responding. Unplug it, plug it back in, "
                                                  "and try again."))
                    continue
                last = time.time()
                if msg[0] == "step" and not stopping:
                    job.message = msg[1]
                    if msg[2] is not None:
                        job.done = msg[2]
                    if msg[3] is not None:
                        job.total = msg[3]
                elif msg[0] == "note":
                    job.note(msg[1])
                elif msg[0] == "result":
                    return msg[1]
                elif msg[0] == "cancelled":
                    raise stopping[1] if stopping else Cancelled()
                elif msg[0] == "error":
                    if msg[2]:
                        job.note(msg[2])
                    raise RuntimeError(msg[1])
        finally:
            proc.join(2)
            if proc.is_alive():
                proc.terminate()   # SIGTERM: child unwinds and closes the session
                proc.join(10)
            if proc.is_alive():
                proc.kill()
                proc.join(1)

    return start_job(kind, device, title, run)
