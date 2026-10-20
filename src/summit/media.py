"""Audio and artwork for the phone. Lossless files (AIFF, WAV, FLAC, ALAC) are transcoded once, on first request,
to 256k AAC under data/cache/stream; MP3 and AAC files are served as they are. Cover art is pulled from the file's
tags and cached as small JPEGs under data/cache/art. Originals are never touched."""

import os
import shutil
import subprocess
import tempfile
import threading
from collections import deque
from pathlib import Path

from .config import DATA

STREAM = DATA / "cache/stream"
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


def needs_transcode(row: dict) -> bool:
    fmt = row["format"]
    if fmt == "mp3":
        return False
    if fmt == "m4a" and row["duration"]:
        return (row["size"] or 0) * 8 / row["duration"] / 1000 > LOSSY_KBPS
    return True


def stream_file(row: dict) -> tuple[Path, str]:
    """(path, media type) of a phone-friendly file for this track, transcoding it now if needed."""
    if not needs_transcode(row):
        return Path(row["path"]), TYPES.get(row["format"], "application/octet-stream")
    out = STREAM / f"{row['id']}.m4a"
    with _lock(row["id"]):
        if not out.exists() or out.stat().st_mtime < (row["mtime"] or 0):
            STREAM.mkdir(parents=True, exist_ok=True)
            fd, tmp = tempfile.mkstemp(suffix=".m4a", dir=STREAM)
            os.close(fd)
            try:
                subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-y", "-i", row["path"], "-map", "0:a:0", "-c:a", "aac_at",
                                "-b:a", "256k", "-movflags", "+faststart", tmp], check=True, capture_output=True, timeout=300)
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

    def add(self, rows: list[dict]) -> None:
        with self.cv:
            queued = {r["id"] for r in self.q}
            for r in rows:
                if needs_transcode(r) and r["id"] not in queued and not (STREAM / f"{r['id']}.m4a").exists():
                    self.q.append(r)
            self.cv.notify()

    def _run(self) -> None:
        while True:
            with self.cv:
                while not self.q:
                    self.cv.wait()
                row = self.q.popleft()
            try:
                stream_file(row)
            except Exception:
                pass


def transcode_all(con, log=print) -> None:
    """Convert every lossless track now (e.g. overnight) instead of on first play."""
    rows = [dict(r) for r in con.execute("SELECT * FROM tracks WHERE missing=0")]
    todo = [r for r in rows if needs_transcode(r) and not (STREAM / f"{r['id']}.m4a").exists()]
    log(f"transcode: {len(todo)} of {len(rows)} tracks")
    for k, r in enumerate(todo, 1):
        try:
            stream_file(r)
        except Exception as e:
            log(f"  failed {r['path']}: {e}")
        if k % 25 == 0 or k == len(todo):
            log(f"  {k}/{len(todo)}")


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
