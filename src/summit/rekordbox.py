"""rekordbox's side of the library: its track info, read from a collection export (rekordbox: File → Export Collection
in xml format).

What rekordbox 7 does with summit.xml (tested by exporting its collection before and after an import): for a track
already in the collection it asks "load information in the tag of the library being imported?". Yes copies the XML's
track fields over its own, and resets the ones that are missing (rating, play count). So the export writes rekordbox's
own attributes for every track it knows, and Summit's tags only for tracks it doesn't. It never writes POSITION_MARK or
TEMPO, so rekordbox keeps its own cues and grids.
"""

import json
import time
import unicodedata
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import unquote

DEFAULT_PATH = Path.home() / "Documents/rekordbox.xml"


def norm(path: str) -> str:
    return unicodedata.normalize("NFC", path)


def path_of(location: str) -> str:
    return norm(unquote(location.removeprefix("file://localhost")))


# ---- settings ----
def get(con, key: str, default=None):
    r = con.execute("SELECT val FROM rb_meta WHERE key=?", (key,)).fetchone()
    return json.loads(r[0]) if r else default


def put(con, key: str, val) -> None:
    con.execute("INSERT OR REPLACE INTO rb_meta VALUES (?,?)", (key, json.dumps(val)))


# ---- import ----
def import_collection(con, path: str | None = None) -> dict:
    """Read a rekordbox collection export: every track's attributes, matched to Summit's by file path."""
    src = Path(path or get(con, "path") or DEFAULT_PATH).expanduser()
    if not src.exists():
        raise FileNotFoundError(f"{src} not found: in rekordbox, File → Export Collection in xml format")
    by_path = {norm(r[0]): r[1] for r in con.execute("SELECT path, id FROM tracks WHERE missing=0")}
    rows, total = [], 0
    for _, el in ET.iterparse(src):
        if el.tag != "TRACK" or el.get("Location") is None:
            continue
        total += 1
        tid = by_path.get(path_of(el.get("Location")))
        if tid:
            rows.append((tid, json.dumps(dict(el.attrib))))
        el.clear()
    con.execute("DELETE FROM rb_tracks")
    con.executemany("INSERT OR REPLACE INTO rb_tracks VALUES (?,?)", rows)
    put(con, "path", str(src))
    put(con, "mtime", src.stat().st_mtime)
    put(con, "imported", time.time())
    con.commit()
    return {"path": str(src), "tracks": total, "matched": len(rows)}


def stale(con) -> bool:
    """The export file changed since it was imported."""
    p = get(con, "path")
    return bool(p) and Path(p).exists() and Path(p).stat().st_mtime != get(con, "mtime")


def status(con) -> dict:
    p = get(con, "path") or str(DEFAULT_PATH)
    exists = Path(p).exists()
    return {"path": p, "exists": exists, "exported": Path(p).stat().st_mtime if exists else None,
            "imported": get(con, "imported"), "stale": stale(con),
            "matched": con.execute("SELECT COUNT(*) FROM rb_tracks").fetchone()[0]}
