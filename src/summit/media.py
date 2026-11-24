"""Audio and artwork for the phone, in two qualities:
- high: lossless files (AIFF, WAV, FLAC, ALAC) as 256k AAC under data/cache/stream; MP3 and AAC as they are.
- low: every track as 128k AAC under data/cache/stream128 (a third of the size, for cellular), unless the file is
  already that small.
Both are made ahead of time by `summit transcode` (the server runs it after each library update), so a first play never
waits on a conversion; a track that isn't ready yet is converted on request. Cover art is pulled from the file's tags
and cached as small JPEGs under data/cache/art. Originals are never touched."""

import os
import shutil
import subprocess
import tempfile
import threading
from collections import deque
from pathlib import Path

from .config import DATA

STREAM = DATA / "cache/stream"
STREAM_LOW = DATA / "cache/stream128"
QUALITIES = ("high", "low")
LOW_KBPS = 128
ART = DATA / "cache/art"
LOSSY_KBPS = 400  # above this an .m4a is ALAC (or an .mp3 is padded with artwork): still fine to send as-is if lossy
TYPES = {"aiff": "audio/aiff", "aif": "audio/aiff", "m4a": "audio/mp4", "mp3": "audio/mpeg", "wav": "audio/wav", "flac": "audio/flac"}

# Services started by launchd get a bare PATH (no Homebrew), so look in the usual places too.
FFMPEG = shutil.which("ffmpeg") or next((p for p in ("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg") if os.path.exists(p)), "ffmpeg")

_locks: dict[str, threading.Lock] = {}
_guard = threading.Lock()


def _lock(key: str) -> threading.Lock:
    with _guard:
        return _locks.setdefault(key, threading.Lock())


def _kbps(row: dict) -> float:
    return (row["size"] or 0) * 8 / row["duration"] / 1000 if row["duration"] else 9999


def needs_transcode(row: dict, q: str = "high") -> bool:
    fmt = row["format"]
    if q == "low":
        return not (fmt in ("mp3", "m4a") and _kbps(row) <= LOW_KBPS + 32)
    if fmt == "mp3":
        return False
    if fmt == "m4a" and row["duration"]:
        return _kbps(row) > LOSSY_KBPS
    return True


def cached_path(row: dict, q: str = "high") -> Path:
    return (STREAM_LOW if q == "low" else STREAM) / f"{row['id']}.m4a"


def is_ready(row: dict, q: str = "high") -> bool:
    """Can this track be sent right away in this quality (no conversion first)?"""
    if not needs_transcode(row, q):
        return True
    out = cached_path(row, q)
    return out.exists() and out.stat().st_mtime >= (row["mtime"] or 0)


def stream_file(row: dict, q: str = "high") -> tuple[Path, str]:
    """(path, media type) of a phone-friendly file for this track in quality q, transcoding it now if needed."""
    q = q if q in QUALITIES else "high"
    if not needs_transcode(row, q):
        return Path(row["path"]), TYPES.get(row["format"], "application/octet-stream")
    out = cached_path(row, q)
    with _lock(f"{q}:{row['id']}"):
        if not is_ready(row, q):
            out.parent.mkdir(parents=True, exist_ok=True)
            fd, tmp = tempfile.mkstemp(suffix=".m4a", dir=out.parent)
            os.close(fd)
            try:
                subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-y", "-i", row["path"], "-map", "0:a:0", "-c:a", "aac_at",
                                "-b:a", "128k" if q == "low" else "256k", "-movflags", "+faststart", tmp],
                               check=True, capture_output=True, timeout=300)
                os.replace(tmp, out)
            finally:
                if os.path.exists(tmp):
                    os.remove(tmp)
    return out, "audio/mp4"


class Prefetch:
    """Transcodes upcoming tracks in the background so they start instantly."""

    def __init__(self):
        self.q: deque[dict] = deque()
        self.cv = threading.Condition()
        threading.Thread(target=self._run, daemon=True).start()

    def add(self, rows: list[dict], q: str = "high") -> None:
        with self.cv:
            queued = {(r["id"], k) for r, k in self.q}
            for r in rows:
                if (r["id"], q) not in queued and not is_ready(r, q):
                    self.q.append((r, q))
            self.cv.notify()

    def _run(self) -> None:
        while True:
            with self.cv:
                while not self.q:
                    self.cv.wait()
                row, q = self.q.popleft()
            try:
                stream_file(row, q)
            except Exception:
                pass


def coverage(con) -> dict:
    """Per quality: tracks that can be sent right away, out of all."""
    rows = [dict(r) for r in con.execute("SELECT id, format, size, duration, mtime FROM tracks WHERE missing=0")]
    return {q: {"ready": sum(is_ready(r, q) for r in rows), "total": len(rows)} for q in QUALITIES}


def transcode_all(con, log=print, workers: int = 4) -> None:
    """Make every track's streaming copies now (both qualities), so the phone never waits on a conversion. Runs at low
    CPU priority; stopping it is safe (the next run picks up where it stopped)."""
    from concurrent.futures import ThreadPoolExecutor

    try:
        os.nice(10)
    except OSError:
        pass
    rows = [dict(r) for r in con.execute("SELECT * FROM tracks WHERE missing=0")]
    todo = [(r, q) for q in ("low", "high") for r in rows if not is_ready(r, q)]  # low first: it's what cellular plays
    log(f"transcode: {len(todo)} tracks")
    done = 0

    def one(item):
        r, q = item
        try:
            stream_file(r, q)
        except Exception as e:
            log(f"  failed {r['path']} ({q}): {e}")

    with ThreadPoolExecutor(workers) as ex:
        for _ in ex.map(one, todo):
            done += 1
            if done % 10 == 0 or done == len(todo):
                log(f"  {done}/{len(todo)}")


def _embedded_art(path: str) -> bytes | None:
    import mutagen

    f = mutagen.File(path)
    if f is None or f.tags is None:
        return None
    tags = f.tags
    if hasattr(tags, "getall"):  # ID3 (mp3, aiff, wav)
        pics = tags.getall("APIC")
        return pics[0].data if pics else None
    if "covr" in tags:  # MP4
        return bytes(tags["covr"][0]) if tags["covr"] else None
    pics = getattr(f, "pictures", None)  # FLAC
    return pics[0].data if pics else None


def art_file(row: dict, size: int) -> Path | None:
    """Square JPEG thumbnail of the track's cover art, or None if it has none."""
    size = max(16, min(1024, size))  # the phone asks for tiny sizes and scales them up as pixel art
    out = ART / f"{row['id']}_{size}.jpg"
    none = ART / f"{row['id']}.none"
    if out.exists():
        return out
    if none.exists():
        return None
    with _lock("art:" + row["id"]):
        if out.exists():
            return out
        ART.mkdir(parents=True, exist_ok=True)
        try:
            data = _embedded_art(row["path"])
        except Exception:
            data = None
        if not data:
            none.touch()
            return None
        fd, src = tempfile.mkstemp(dir=ART)
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
        try:  # sips ships with macOS: crop to a centred square, then scale
            info = subprocess.run(["sips", "-g", "pixelWidth", "-g", "pixelHeight", src], capture_output=True, text=True, timeout=30).stdout
            w, h = (int(line.split()[-1]) for line in info.splitlines()[1:3])
            side = str(min(w, h))
            subprocess.run(["sips", "-s", "format", "jpeg", "--cropToHeightWidth", side, side, src, "--out", src + ".sq.jpg"],
                           check=True, capture_output=True, timeout=30)
            subprocess.run(["sips", "-s", "format", "jpeg", "-s", "formatOptions", "82", "-Z", str(size), src + ".sq.jpg", "--out", str(out)],
                           check=True, capture_output=True, timeout=30)
        except Exception:
            none.touch()
            return None
        finally:
            for p in (src, src + ".sq.jpg"):
                if os.path.exists(p):
                    os.remove(p)
    return out if out.exists() else None
