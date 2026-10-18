"""Step 1: walk the library, read tags, upsert tracks. Moved files keep their id (matched by content hash)."""

import hashlib
import uuid
from pathlib import Path

import mutagen

from . import db
from .config import AUDIO_EXTS, LIBRARY, MAX_DURATION, MIN_DURATION

# Tag frame names per container, for files mutagen can't open in "easy" mode (AIFF, WAV).
ID3 = {"artist": "TPE1", "title": "TIT2", "album": "TALB", "genre": "TCON"}


def content_hash(path: Path, size: int) -> str:
    with path.open("rb") as f:
        f.seek(max(0, size // 2 - 131072))
        return hashlib.sha1(f.read(262144)).hexdigest()[:20]


def read_tags(path: Path) -> dict | None:
    try:
        f = mutagen.File(path, easy=True)
    except Exception:
        f = None
    if f is None or f.info is None:
        return None
    out = {"duration": float(getattr(f.info, "length", 0) or 0)}
    tags = f.tags or {}
    for k, frame in ID3.items():
        v = None
        try:
            v = tags.get(k) or tags.get(frame)
        except Exception:
            pass
        if v is not None and not isinstance(v, str):
            v = v[0] if isinstance(v, list) else getattr(v, "text", [str(v)])[0]
        out[k] = str(v).strip() if v else None
    return out


def scan(root: Path = LIBRARY, log=print) -> dict:
    con = db.connect()
    known = {r["path"]: dict(r) for r in con.execute("SELECT * FROM tracks")}
    by_hash = {r["hash"]: r for r in known.values() if r["hash"]}
    seen, stats = set(), {"new": 0, "updated": 0, "moved": 0, "skipped": 0, "missing": 0, "removed": 0}
    # Files outside MIN_DURATION..MAX_DURATION aren't tracks (samples, DJ mixes): drop any we already know.
    long = [r["id"] for r in known.values() if (r["duration"] or 0) > MAX_DURATION]
    if long:
        db.purge(con, long)
        stats["removed"] = len(long)
        known = {p: r for p, r in known.items() if r["id"] not in set(long)}
    files = sorted(p for p in root.rglob("*") if p.suffix.lower() in AUDIO_EXTS and not p.name.startswith("."))
    for n, p in enumerate(files, 1):
        path, st = str(p), p.stat()
        seen.add(path)
        row = known.get(path)
        if row and row["size"] == st.st_size and row["mtime"] == st.st_mtime and not row["missing"]:
            continue
        tags = read_tags(p)
        if not tags or not MIN_DURATION <= tags["duration"] <= MAX_DURATION:
            stats["skipped"] += 1
            continue
        h = content_hash(p, st.st_size)
        meta = dict(
            artist=tags["artist"] or p.parent.parent.name, title=tags["title"] or p.stem, album=tags["album"] or p.parent.name,
            genre=tags["genre"], duration=tags["duration"], format=p.suffix.lower().lstrip("."), size=st.st_size, mtime=st.st_mtime, hash=h,
        )
        moved = by_hash.get(h) if not row else None
        if moved and not Path(moved["path"]).exists():
            con.execute("UPDATE tracks SET path=?, missing=0 WHERE id=?", (path, moved["id"]))
            row, stats["moved"] = moved, stats["moved"] + 1
        if row:
            con.execute(f"UPDATE tracks SET {', '.join(k + '=?' for k in meta)}, missing=0 WHERE id=?", (*meta.values(), row["id"]))
            stats["updated"] += 0 if moved else 1
        else:
            con.execute(
                f"INSERT INTO tracks(id, path, added_at, {', '.join(meta)}) VALUES (?,?,?,{','.join('?' * len(meta))})",
                (uuid.uuid4().hex[:16], path, db.now(), *meta.values()),
            )
            stats["new"] += 1
        if n % 500 == 0:
            con.commit()
            log(f"  scanned {n}/{len(files)}")
    for path, row in known.items():
        if path not in seen and not row["missing"] and not Path(path).exists():
            con.execute("UPDATE tracks SET missing=1 WHERE id=?", (row["id"],))
            stats["missing"] += 1
    con.commit()
    total = con.execute("SELECT COUNT(*) FROM tracks WHERE missing=0").fetchone()[0]
    log(f"scan: {stats} -> {total} tracks")
    return stats
