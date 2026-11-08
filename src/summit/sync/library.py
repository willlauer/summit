"""What device sync can send: Summit's playlists (and Liked) plus, when there is one, the rekordbox collection export.

Both sides end up as the same records, keyed by the file's path relative to the music folder ("rel"), which is also
what the Garmin's track index and the FiiO card's layout are keyed by. So a track in a Summit playlist and the same
file in rekordbox are one track on the device.

Playlist names are what the device shows and what its files are named after: rekordbox playlists flatten their folder
path ("Gig - Warmup", as rekordbox_sd always has); Summit playlists flatten theirs the same way. When both sides have
the same name, Summit's keeps it and the rekordbox one becomes "<name> (rekordbox)".

The FiiO card mirrors the rekordbox collection plus every track in a Summit playlist; the Garmin gets the playlists
you pick.
"""

import os
import threading
import time

from . import rekordbox_sd as rb
from .common import load_config


def resolve_xml(cfg):
    if cfg.get("xml_path"):
        return os.path.expanduser(cfg["xml_path"])
    for p in ("~/Desktop/rekordbox.xml", "~/Documents/rekordbox.xml"):
        p = os.path.expanduser(p)
        if os.path.isfile(p):
            return p
    return os.path.expanduser("~/Desktop/rekordbox.xml")


def library_root(cfg):
    return os.path.realpath(os.path.expanduser(cfg["library_root"])).rstrip("/") + "/"


# ─── Caches: the music folder's file index and the parsed rekordbox export ──

_lock = threading.Lock()
_index = {"root": None, "at": 0.0, "map": {}}
_rbx = {"key": None, "val": None}
INDEX_TTL = 600     # re-walk the music folder at most this often…
INDEX_RETRY = 30    # …or sooner when a Summit track isn't in it (a file added since)


def _file_index(root, want_fresh=False):
    age = time.time() - _index["at"]
    if _index["root"] != root or age > INDEX_TTL or (want_fresh and age > INDEX_RETRY):
        _index.update(root=root, at=time.time(), map=rb.library_index(root))
    return _index["map"]


def _rekordbox(cfg, root, index, force):
    """(status, tracks, playlists) from the export, cached until the file changes. Never raises: a missing or broken
    export is reported in status and simply contributes nothing."""
    xml = resolve_xml(cfg)
    try:
        mtime = os.path.getmtime(xml)
    except OSError:
        return {"path": xml, "mtime": None, "error": "No rekordbox export (File > Export Collection in xml format)."}, {}, [], [], [], []
    key = (xml, mtime, root)
    if force or _rbx["key"] != key:
        try:
            xroot, tracks, streaming, outside, missing = rb.parse_collection(xml, root, index)
            playlists = rb.parse_playlists(xroot)
            val = ({"path": xml, "mtime": mtime, "error": None}, tracks, playlists, streaming, outside, missing)
        except SystemExit as e:  # rekordbox_sd refuses via sys.exit
            val = ({"path": xml, "mtime": mtime, "error": str(e.code)}, {}, [], [], [], [])
        except Exception as e:
            val = ({"path": xml, "mtime": mtime, "error": f"Could not read {xml}: {e}"}, {}, [], [], [], [])
        _rbx.update(key=key, val=val)
    return _rbx["val"]


# ─── The library ─────────────────────────────────────────────────────────────

class Library:
    """Plain data (it is pickled into the Garmin worker process)."""

    def __init__(self, root, tracks, playlists, mirror, rekordbox, counts):
        self.library_root = root
        self.tracks = tracks              # id -> record; ids are "rb:<TrackID>" or "s:<summit id>"
        self.syncable = {i: r for i, r in tracks.items() if r["rel"]}
        self.playlists = [(p["name"], p["ids"]) for p in playlists]  # what garmin.py / fiio.py iterate
        self.meta = playlists             # the same, with source / summit id / folder for the UI
        self.mirror = {i: self.syncable[i] for i in mirror if i in self.syncable}  # what the FiiO card holds
        self.rekordbox = rekordbox
        self.counts = counts
        self.missing = counts.pop("_missing_rels")

    def playlist_tracks(self, name):
        for n, ids in self.playlists:
            if n == name:
                return [self.syncable[i] for i in ids if i in self.syncable]
        return []

    def summary(self):
        pls = []
        for p in self.meta:
            ok = [self.syncable[i] for i in p["ids"] if i in self.syncable]
            pls.append({"name": p["name"], "source": p["source"], "id": p.get("id"), "label": p.get("label", p["name"]),
                        "count": len(p["ids"]), "syncable": len(ok), "bytes": sum(r["size"] for r in ok)})
        return {
            "library_root": self.library_root,
            "rekordbox": self.rekordbox,
            "tracks": len(self.syncable),
            "mirror": len(self.mirror), "mirror_bytes": sum(r["size"] for r in self.mirror.values()),
            **self.counts,
            "playlists": pls,
        }


def _record(path, artist, name, secs, root, index, size=None):
    """A sync record for one file, or (None, why) when it can't go to a device."""
    path = rb.nfc(path)
    if not path.startswith(root):
        return None, "outside"
    real = index.get(path[len(root):].casefold())
    if real is None:
        return None, "missing"
    full = root + real
    try:
        size = size or os.path.getsize(full)
    except OSError:
        return None, "missing"
    return {"artist": artist or "", "name": name or "", "secs": str(secs or 0), "path": full, "rel": real,
            "card": rb.card_name(real), "size": size}, None


def build(summit, force=False):
    """summit: {"tracks": {id: {path, artist, title, duration, size}}, "playlists": [{id, name, folder_path, ids}],
    "liked": [ids]}, as the server hands it over."""
    cfg = load_config()
    root = library_root(cfg)
    if not os.path.isdir(root):
        raise FileNotFoundError(f"Music library folder not found: {root} (change it in Settings › Devices)")
    with _lock:
        index = _file_index(root)
        if any(rb.nfc(t["path"]).startswith(root) and rb.nfc(t["path"])[len(root):].casefold() not in index
               for t in summit["tracks"].values()):
            index = _file_index(root, want_fresh=True)
        status, rtracks, rplaylists, streaming, outside, missing = _rekordbox(cfg, root, index, force)

    tracks, counts = {}, {"streaming": 0, "outside": 0, "missing": 0}
    missing_rels = list(missing)
    # rekordbox's records (already resolved against the index by rekordbox_sd)
    for tid, r in rtracks.items():
        tracks["rb:" + tid] = dict(r, size=os.path.getsize(r["path"]) if r["rel"] else 0, card=rb.card_name(r["rel"]) if r["rel"] else None)
    counts["streaming"] += len(streaming) + sum(1 for p in outside if not p.startswith("/"))  # SoundCloud: file://localhostsoundcloud:…
    counts["outside"] += sum(1 for p in outside if p.startswith("/"))
    counts["missing"] += len(missing)

    # Summit's tracks that are in a playlist (only those can reach a device)
    wanted = {i for p in summit["playlists"] for i in p["ids"]} | set(summit.get("liked", []))
    for sid in wanted:
        t = summit["tracks"].get(sid)
        if not t:
            continue
        rec, why = _record(t["path"], t["artist"], t["title"], int(t["duration"] or 0), root, index, t.get("size"))
        if rec is None:
            counts[why] += 1
            rec = {"artist": t["artist"] or "", "name": t["title"] or "", "secs": "0", "path": None, "rel": None, "why": why}
        tracks["s:" + sid] = rec

    # Playlists: Summit's first (they keep their names on a clash), then rekordbox's.
    pls, taken = [], set()

    def unique(name, suffix):
        out, n = name, 2
        while out.casefold() in taken:
            out = f"{name} ({suffix})" if n == 2 and suffix else f"{name} ({n})"
            n += 1
        taken.add(out.casefold())
        return out

    if summit.get("liked"):
        pls.append({"name": unique("Liked", ""), "label": "Liked", "source": "summit", "id": "liked", "ids": ["s:" + i for i in summit["liked"]]})
    for p in summit["playlists"]:
        name = rb.flatten(p["folder_path"] + [p["name"]])
        pls.append({"name": unique(name, ""), "label": " › ".join(p["folder_path"] + [p["name"]]), "source": "summit", "id": p["id"],
                    "ids": ["s:" + i for i in p["ids"]]})
    for name, ids in rplaylists:
        pls.append({"name": unique(name, "rekordbox"), "label": name, "source": "rekordbox", "ids": ["rb:" + i for i in ids]})

    mirror = {i for i in tracks if i.startswith("rb:")} | {i for p in pls if p["source"] == "summit" for i in p["ids"]}
    status = dict(status, tracks=sum(1 for i in tracks if i.startswith("rb:")), playlists=len(rplaylists))
    counts["_missing_rels"] = missing_rels
    return Library(root, tracks, pls, mirror, status, counts)
