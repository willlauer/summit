"""The only door into rekordbox's own files. Every change Summit makes to rekordbox goes through `write` or `restore`
here, and nothing else in Summit may touch rekordbox's library (tests/test_rbguard.py fails the build if any other
module imports pyrekordbox or names rekordbox's folders).

The lock (data/rekordbox_lock.json; locked when missing) is set from the app: Tools › rekordbox. While locked, `write`
and `restore` do nothing and return {"noop": True, "locked": True}; each attempt is logged (data/rekordbox_guard.jsonl)
and shown in the app. "Unlock once" re-locks after the next write.

Mechanical backstop: `install()` (called by the server and the CLI at start) adds a process-wide audit hook that
raises PermissionError for any Python-level write, delete, rename or copy into rekordbox's folders unless it happens
inside this module's guarded section. Reads go through `snapshot()`, which opens a temporary copy, so even pyrekordbox
(whose SQLCipher writes the hook can't see) never opens the live library except inside `write`.
"""

import contextlib
import contextvars
import json
import os
import shutil
import sys
import tempfile
import time
import traceback
from pathlib import Path

from .config import DATA

LOCK_FILE = DATA / "rekordbox_lock.json"
LOG_FILE = DATA / "rekordbox_guard.jsonl"
BACKUPS = DATA / "rb_backups"
KEEP = 10  # backups kept
PROTECTED = [Path.home() / "Library/Pioneer", Path.home() / "Library/Application Support/Pioneer"]

_allowed = contextvars.ContextVar("rekordbox_write_allowed", default=False)
_installed = False


# ---- the lock ----
def _state() -> dict:
    try:
        return json.loads(LOCK_FILE.read_text())
    except (OSError, ValueError):
        return {"locked": True, "once": False, "at": None}


def locked() -> bool:
    return bool(_state().get("locked", True))


def set_locked(lock: bool, once: bool = False) -> dict:
    """Lock, or unlock (`once`: re-lock after the next write)."""
    st = {"locked": bool(lock), "once": bool(once) and not lock, "at": time.time()}
    DATA.mkdir(parents=True, exist_ok=True)
    LOCK_FILE.write_text(json.dumps(st))
    return st


def _record(action: str) -> None:
    caller = next((f"{Path(f.filename).stem}.{f.name}" for f in reversed(traceback.extract_stack()[:-2])
                   if "rbguard" not in f.filename), "?")
    with LOG_FILE.open("a") as f:
        f.write(json.dumps({"at": time.time(), "action": action, "caller": caller}) + "\n")


def blocked(n: int = 10) -> list[dict]:
    """The latest writes the lock turned into no-ops."""
    try:
        return [json.loads(x) for x in LOG_FILE.read_text().splitlines()[-n:]][::-1]
    except (OSError, ValueError):
        return []


def status() -> dict:
    return _state() | {"blocked": blocked()}


# ---- audit hook ----
_WRITE_FLAGS = os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND
_PATH_EVENTS = {"os.remove": (0,), "os.rmdir": (0,), "os.rename": (0, 1), "os.truncate": (0,), "os.mkdir": (0,),
                "os.chmod": (0,), "os.chown": (0,), "os.symlink": (1,), "os.link": (1,), "os.utime": (0,),
                "shutil.copyfile": (1,), "shutil.copytree": (1,), "shutil.rmtree": (0,), "shutil.move": (0, 1),
                "sqlite3.connect": (0,)}


def _protected(p) -> bool:
    if not isinstance(p, (str, bytes, os.PathLike)):
        return False
    try:
        s = os.path.realpath(os.fsdecode(p))
    except (TypeError, ValueError):
        return False
    return any(s == str(r) or s.startswith(str(r) + os.sep) for r in PROTECTED)


def _hook(event: str, args: tuple) -> None:
    if event == "open":
        path, mode, flags = (tuple(args) + (None, None, None))[:3]
        writes = (isinstance(mode, str) and any(c in mode for c in "wax+")) or (isinstance(flags, int) and flags & _WRITE_FLAGS)
        targets = [path] if writes else []
    elif event in _PATH_EVENTS:
        targets = [args[i] for i in _PATH_EVENTS[event] if i < len(args)]
    else:
        return
    if targets and not _allowed.get() and any(_protected(t) for t in targets):
        raise PermissionError(f"rekordbox is protected: {event} {targets[0]!s} outside summit.rbguard (see rbguard.py)")


def install() -> None:
    """Process-wide; can't be removed once added."""
    global _installed
    if not _installed:
        sys.addaudithook(_hook)
        _installed = True


@contextlib.contextmanager
def _unlocked_section():
    tok = _allowed.set(True)
    try:
        yield
    finally:
        _allowed.reset(tok)


# ---- rekordbox's library ----
def db_path() -> Path:
    from pyrekordbox.config import get_config

    cfg = get_config("rekordbox7") or get_config("rekordbox6")
    p = cfg.get("db_path") if cfg else None
    if not p:
        raise FileNotFoundError("rekordbox's library (master.db) wasn't found")
    return Path(p)


def running() -> bool:
    from pyrekordbox.utils import get_rekordbox_pid

    return bool(get_rekordbox_pid())


def _open(path: Path):
    import logging

    from pyrekordbox import MasterDatabase

    logging.getLogger("pyrekordbox").setLevel(logging.ERROR)  # it warns on every open while rekordbox runs
    return MasterDatabase(path)


@contextlib.contextmanager
def snapshot():
    """Read-only: a MasterDatabase on a temporary copy of rekordbox's library."""
    src = db_path()
    tmp = Path(tempfile.mkdtemp(prefix="summit-rb-"))
    try:
        for f in src.parent.glob(src.name + "*"):
            shutil.copy2(f, tmp / f.name)
        db = _open(tmp / src.name)
        try:
            yield db
        finally:
            db.close()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def models():
    from pyrekordbox.masterdb import models as M

    return M


def _backup(note: str) -> Path:
    src = db_path()
    dest = BACKUPS / time.strftime("%Y%m%d-%H%M%S")
    dest.mkdir(parents=True, exist_ok=True)
    for f in src.parent.glob(src.name + "*"):  # master.db, -wal, -shm
        shutil.copy2(f, dest / f.name)
    (dest / "summit.json").write_text(json.dumps({"note": note}))
    for old in sorted(p for p in BACKUPS.iterdir() if p.is_dir())[:-KEEP]:
        shutil.rmtree(old)
    return dest


def backups() -> list[dict]:
    if not BACKUPS.exists():
        return []
    out = []
    for p in sorted((p for p in BACKUPS.iterdir() if p.is_dir()), reverse=True):
        meta = p / "summit.json"
        out.append({"stamp": p.name, **(json.loads(meta.read_text()) if meta.exists() else {})})
    return out


def _after_write() -> None:
    if _state().get("once"):
        set_locked(True)


def write(action: str, fn) -> dict:
    """Run fn(db) on rekordbox's live library and commit, after a backup; a no-op while locked. fn returns a dict of
    what it changed; {"unchanged": True, ...} rolls back and drops the backup."""
    if locked():
        _record(action)
        return {"noop": True, "locked": True, "action": action}
    if running():
        raise RuntimeError("quit rekordbox first: it would overwrite the change when it closes")
    saved = _backup(f"before: {action}")
    with _unlocked_section():
        db = _open(db_path())
        try:
            res = fn(db)
            if res.get("unchanged"):
                db.rollback()
                shutil.rmtree(saved)  # a no-op mustn't push a real backup out
                return res
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()
    meta = saved / "summit.json"
    meta.write_text(json.dumps({"note": f"before: {action}", **{k: v for k, v in res.items() if isinstance(v, (int, str))}}))
    _after_write()
    return res | {"backup": saved.name}


def restore(stamp: str) -> dict:
    """Put a backup's master.db back (rekordbox closed); a no-op while locked."""
    src = BACKUPS / stamp
    if "/" in stamp or ".." in stamp or not (src / db_path().name).exists():
        raise FileNotFoundError(f"no backup {stamp}")
    if locked():
        _record(f"restore {stamp}")
        return {"noop": True, "locked": True, "action": f"restore {stamp}"}
    if running():
        raise RuntimeError("quit rekordbox first")
    dest = db_path()
    safety = _backup(f"before restoring {stamp}")  # so a restore can be undone too
    with _unlocked_section():
        for f in dest.parent.glob(dest.name + "-*"):
            f.unlink()  # a stale -wal would be replayed over the restored file
        for f in src.glob(dest.name + "*"):
            shutil.copy2(f, dest.parent / f.name)
    _after_write()
    return {"restored": stamp, "backup": safety.name}
