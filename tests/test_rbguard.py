"""rekordbox can only be changed through summit.rbguard, and not at all while it's locked.

Run: uv run --with pytest pytest tests
"""

import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

SRC = Path(__file__).resolve().parents[1] / "src" / "summit"
# Ways into rekordbox's files. Only rbguard.py may use them.
FORBIDDEN = re.compile(r"pyrekordbox|sqlcipher|Library/Pioneer|Application Support/Pioneer|master\.db|MasterDatabase", re.I)


def test_only_rbguard_touches_rekordbox():
    offenders = []
    for f in SRC.rglob("*"):
        if f.suffix not in (".py", ".js", ".html") or f.name == "rbguard.py":
            continue
        for n, line in enumerate(f.read_text(errors="ignore").splitlines(), 1):
            if FORBIDDEN.search(line):
                offenders.append(f"{f.relative_to(SRC)}:{n}: {line.strip()}")
    assert not offenders, "rekordbox access outside rbguard.py:\n" + "\n".join(offenders)


HOOK_SCRIPT = r"""
import os, shutil, sqlite3, sys
from pathlib import Path
from summit import rbguard
root = Path(sys.argv[1]); rbguard.PROTECTED = [root]
rbguard.install()
target = root / "master.db"
attempts = {
    "open w": lambda: open(target, "w"),
    "open a": lambda: open(target, "ab"),
    "os.open": lambda: os.open(target, os.O_WRONLY),
    "remove": lambda: os.remove(target),
    "rename": lambda: os.rename(target, root / "x"),
    "rename into": lambda: os.rename(sys.argv[2], root / "y"),
    "copy into": lambda: shutil.copy2(sys.argv[2], root / "z"),
    "rmtree": lambda: shutil.rmtree(root),
    "sqlite": lambda: sqlite3.connect(target),
    "path write": lambda: target.write_text("x"),
}
for name, fn in attempts.items():
    try:
        fn(); print("ALLOWED", name)
    except PermissionError:
        print("BLOCKED", name)
print("READ", open(target).read())
with rbguard._unlocked_section():
    target.write_text("ok"); print("GUARDED", target.read_text())
"""


def test_audit_hook_blocks_writes(tmp_path):
    root = tmp_path / "Pioneer"
    root.mkdir()
    (root / "master.db").write_text("orig")
    outside = tmp_path / "outside.txt"
    outside.write_text("x")
    out = subprocess.run([sys.executable, "-c", HOOK_SCRIPT, str(root), str(outside)], capture_output=True, text=True, check=True).stdout
    assert "ALLOWED" not in out, out
    assert out.count("BLOCKED") == 10, out
    assert "READ orig" in out and "GUARDED ok" in out


def test_locked_write_is_a_noop(tmp_path, monkeypatch):
    from summit import rbguard

    monkeypatch.setattr(rbguard, "LOCK_FILE", tmp_path / "lock.json")
    monkeypatch.setattr(rbguard, "LOG_FILE", tmp_path / "log.jsonl")
    called = []
    assert rbguard.locked()  # no lock file: locked
    res = rbguard.write("test", lambda db: called.append(1) or {})
    assert res == {"noop": True, "locked": True, "action": "test"} and not called
    assert rbguard.blocked()[0]["action"] == "test"
    monkeypatch.setattr(rbguard, "db_path", lambda: tmp_path / "master.db")
    (tmp_path / "master.db").write_text("x")
    with pytest.raises(FileNotFoundError):
        rbguard.restore("../x")
    (tmp_path / "bk").mkdir()
    monkeypatch.setattr(rbguard, "BACKUPS", tmp_path / "bk")
    (tmp_path / "bk" / "s1").mkdir()
    (tmp_path / "bk" / "s1" / "master.db").write_text("old")
    assert rbguard.restore("s1")["noop"] and (tmp_path / "master.db").read_text() == "x"


def test_unlock_once_relocks(tmp_path, monkeypatch):
    from summit import rbguard

    monkeypatch.setattr(rbguard, "LOCK_FILE", tmp_path / "lock.json")
    rbguard.set_locked(False, once=True)
    assert not rbguard.locked()
    rbguard._after_write()
    assert rbguard.locked()
    rbguard.set_locked(False)
    rbguard._after_write()
    assert not rbguard.locked()
