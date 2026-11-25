"""FiiO MicroSD card (the JM21 player): the whole library or only the picked playlists' tracks, with each picked
playlist (Summit, rekordbox, Music app) as an .m3u8 in Documents/ for any player, and Summit/library.json plus
Summit/art/ for the Summit app on the player, which plays straight from the card (library.py decides what is offered).

The comparison and copy rules are rekordbox_sd.py's main(), split into a
plan step (no writes) and an apply step so the UI can preview first.
Every write still goes through rekordbox_sd.Card's guard().
"""

import json
import os
import shutil
import time

from . import rekordbox_sd as rb
from .common import load_config, human

PLAYLIST_DIR = "Documents"
MUSIC_DIR = "Music"
APP_DIR = "Summit"  # library.json + art/ for the Summit app on the player
SOURCE_FOLDERS = {"rekordbox": -1, "itunes": -2}  # the app files other sources' playlists in these (read-only) folders


def mount_point(cfg=None):
    cfg = cfg or load_config()
    return os.path.realpath(os.path.expanduser(cfg["fiio_mount"]))


def status(cfg=None):
    m = mount_point(cfg)
    connected = os.path.isdir(m) and os.path.ismount(m)
    out = {"id": "fiio", "name": "FiiO MicroSD", "kind": "MicroSD card", "connected": connected,
           "mount": m, "detail": m if connected else f"Insert the card (expects {m})"}
    if connected:
        du = shutil.disk_usage(m)
        out.update(free=du.free, capacity=du.total, writable=os.access(m, os.W_OK))
    return out


def _card():
    try:
        return rb.Card(mount_point())
    except SystemExit as e:
        raise RuntimeError(str(e.code))


def _count_m3u(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return sum(1 for ln in fh if ln.strip() and not ln.startswith("#"))
    except OSError:
        return None


def contents(job=None):
    card = _card()
    music = os.path.join(card.root, MUSIC_DIR)
    docs = os.path.join(card.root, PLAYLIST_DIR)
    if job:
        job.step("Scanning card…")
    have = rb.card_audio(card, music)
    total = 0
    for p in have.values():
        try:
            total += os.path.getsize(p)
        except OSError:
            pass
    playlists = []
    if os.path.isdir(docs):
        for fn in sorted(os.listdir(docs), key=str.casefold):
            if fn.lower().endswith((".m3u", ".m3u8")) and not fn.startswith("._"):
                playlists.append({"name": os.path.splitext(fn)[0],
                                  "tracks": _count_m3u(os.path.join(docs, fn))})
    du = shutil.disk_usage(card.root)
    return {"tracks": len(have), "track_bytes": total, "playlists": playlists,
            "free": du.free, "capacity": du.total}


def picked(lib, cfg=None):
    """(tracks for the card, playlists for the card) from the saved picks (Settings live in the sync config)."""
    cfg = cfg or load_config()
    names = cfg.get("fiio_playlists")
    if names is not None:
        names = [n for n in names if any(n == m for m, _ in lib.playlists)]
    return lib.selection(names, cfg.get("fiio_scope") or "library")


def plan(lib, job=None):
    """What a sync would do. Mirrors rekordbox_sd.main() up to --apply."""
    cfg = load_config()
    card = _card()
    mirror, playlists = picked(lib, cfg)
    music_dir = os.path.join(card.root, MUSIC_DIR)
    docs_dir = os.path.join(card.root, PLAYLIST_DIR)
    if job:
        job.step("Comparing library with card…")

    want, origin = {}, {}
    for r in mirror.values():
        prev = origin.setdefault(r["card"].casefold(), r["rel"])
        if prev != r["rel"]:
            raise RuntimeError(f"Two tracks map to the same card path {r['card']!r}:\n"
                               f"{prev}\n{r['rel']}\nRename one of them in Music.app.")
        want[r["card"]] = r["path"]
    orphan_rels = {rb.card_name(rb.nfc(m)) for m in lib.missing}

    bad = rb.check_exfat_safe(want)
    if bad:
        raise RuntimeError(f"{len(bad)} paths contain characters exFAT cannot store, e.g. "
                           f"{bad[0]!r}. Rename them in Music.app.")

    have = rb.card_audio(card, music_dir)
    have_ci = {r.casefold(): r for r in have}

    to_add, to_touch, unchanged = [], [], 0
    for rel, src in sorted(want.items()):
        card_rel = have_ci.get(rel.casefold())
        dst = have[card_rel] if card_rel else None
        if dst is None:
            to_add.append(rel)
            continue
        s, d = os.stat(src), os.stat(dst)
        if s.st_size != d.st_size:
            to_add.append(rel)
        else:
            unchanged += 1
            if int(s.st_mtime) != int(d.st_mtime):
                to_touch.append((src, dst))
    orphan_ci = {r.casefold() for r in orphan_rels}
    want_ci = {r.casefold() for r in want}
    keep = {r for r in have if r.casefold() in orphan_ci}
    to_delete = sorted(r for r in have if r.casefold() not in want_ci and r not in keep)

    add_ci = {r.casefold(): r for r in to_add}
    to_rename = []
    for old in to_delete:
        new = add_ci.get(rb.card_name(old).casefold())
        if new and os.path.getsize(have[old]) == os.path.getsize(want[new]):
            to_rename.append((old, new))
            del add_ci[new.casefold()]
    renamed_old = {o for o, _ in to_rename}
    renamed_new = {n for _, n in to_rename}
    to_delete = [r for r in to_delete if r not in renamed_old]
    to_add = [r for r in to_add if r not in renamed_new]

    add_bytes = sum(os.path.getsize(want[r]) for r in to_add)
    del_bytes = sum(os.path.getsize(have[r]) for r in to_delete)
    junk = rb.sidecars(card)
    junk_bytes = sum(os.path.getsize(p) for p in junk if os.path.exists(p))
    stale = sorted(f for f in os.listdir(music_dir) if f.lower().endswith((".m3u", ".m3u8"))) \
        if os.path.isdir(music_dir) else []
    new_names = {f"{name}.m3u8" for name, _ in playlists}
    old_names = sorted(f for f in os.listdir(docs_dir)
                       if f.lower().endswith((".m3u", ".m3u8")) and not f.startswith("._")) \
        if os.path.isdir(docs_dir) else []
    pl_delete = sorted(set(old_names) - new_names)

    free = shutil.disk_usage(card.root).free
    net = add_bytes - del_bytes - junk_bytes
    return {
        "device": "fiio",
        "card": card.root,
        "android_root": cfg["fiio_android_root"].rstrip("/"),
        "want": want, "have": have, "to_add": to_add, "to_touch": to_touch,
        "to_delete": to_delete, "to_rename": to_rename, "stale": stale, "pl_delete": pl_delete,
        "playlists": playlists, "mirror": mirror,
        "summary": {
            "unchanged": unchanged,
            "add": len(to_add), "add_bytes": add_bytes,
            "delete": len(to_delete), "delete_bytes": del_bytes,
            "rename": len(to_rename),
            "kept_orphans": len(keep),
            "tracks": len(want),
            "playlists_write": len(playlists),
            "playlists_delete": len(pl_delete) + len(stale),
            "sidecars": len(junk), "sidecar_bytes": junk_bytes,
            "free": free, "net": net, "fits": net <= free,
            "add_sample": to_add[:15], "delete_sample": to_delete[:15],
            "skipped_streaming": lib.counts["streaming"], "skipped_missing": lib.counts["missing"],
            "skipped_outside": lib.counts["outside"],
        },
    }


def apply(lib, job):
    p = plan(lib, job)
    s = p["summary"]
    if not s["fits"]:
        raise RuntimeError(f"Not enough space on the card: need {human(s['net'])}, have {human(s['free'])}")
    card = _card()
    music_dir = os.path.join(card.root, MUSIC_DIR)
    docs_dir = os.path.join(card.root, PLAYLIST_DIR)
    want, have = p["want"], p["have"]
    playlists = p["playlists"]
    total = len(p["to_rename"]) + len(p["to_add"]) + len(p["to_delete"]) + len(playlists)
    n = 0
    errors = 0

    for old, new in p["to_rename"]:
        n += 1
        job.step(f"Renaming {new}", n, total)
        try:
            card.rename(have[old], os.path.join(music_dir, new))
            job.note(f"renamed  {old} -> {new}")
        except OSError as e:
            errors += 1
            job.note(f"FAIL rename {old}: {e}")

    copied_bytes = 0
    for rel in p["to_add"]:
        n += 1
        job.step(f"Copying {rel}", n, total)
        try:
            dst = os.path.join(music_dir, rel)
            card.copy_in(want[rel], dst)
            copied_bytes += os.path.getsize(dst)
            job.note(f"+ {rel}")
        except OSError as e:
            errors += 1
            job.note(f"FAIL copy {rel}: {e}")
    for src, dst in p["to_touch"]:
        try:
            card.restamp(src, dst)
        except OSError:
            pass

    for rel in p["to_delete"]:
        n += 1
        job.step(f"Deleting {rel}", n, total)
        try:
            card.unlink(have[rel])
            job.note(f"- {rel}")
        except OSError as e:
            errors += 1
            job.note(f"FAIL delete {rel}: {e}")

    for name, ids in playlists:
        n += 1
        job.step(f"Writing playlist {name}", n, total)
        entries = [lib.syncable[i] for i in ids if i in lib.syncable]
        card.write_text(os.path.join(docs_dir, f"{name}.m3u8"), rb.render(entries, p["android_root"]))
    for fn in p["pl_delete"]:
        try:
            card.unlink(os.path.join(docs_dir, fn))
            job.note(f"- playlist {fn}")
        except OSError as e:
            job.note(f"FAIL delete playlist {fn}: {e}")
    for fn in p["stale"]:
        try:
            card.unlink(os.path.join(music_dir, fn))
        except OSError:
            pass

    job.step("Cleaning up sidecar files…")
    for path in rb.sidecars(card):
        try:
            card.unlink(path)
        except OSError:
            pass
    rb.prune_empty(card, music_dir)
    app_tracks = write_app_library(card, lib, p, job)
    return {"copied": len(p["to_add"]), "copied_bytes": copied_bytes, "deleted": len(p["to_delete"]),
            "renamed": len(p["to_rename"]), "playlists": len(playlists), "errors": errors, "app_tracks": app_tracks,
            "free": shutil.disk_usage(card.root).free}


def wipe(job):
    """Delete every audio file under Music/ and every playlist in Documents/.
    Leaves PIONEER/ (CDJ export) and anything else on the card alone."""
    card = _card()
    music_dir = os.path.join(card.root, MUSIC_DIR)
    docs_dir = os.path.join(card.root, PLAYLIST_DIR)
    have = rb.card_audio(card, music_dir)
    pls = []
    for d in (docs_dir, music_dir):
        if os.path.isdir(d):
            pls += [os.path.join(d, f) for f in os.listdir(d) if f.lower().endswith((".m3u", ".m3u8"))]
    app_lib = os.path.join(card.root, APP_DIR, "library.json")
    items = list(have.values()) + pls + ([app_lib] if os.path.exists(app_lib) else [])
    for i, path in enumerate(items, 1):
        job.step(f"Deleting {os.path.basename(path)}", i, len(items))
        try:
            card.unlink(path)
        except OSError as e:
            job.note(f"FAIL {path}: {e}")
    for path in rb.sidecars(card):
        try:
            card.unlink(path)
        except OSError:
            pass
    rb.prune_empty(card, music_dir)
    return {"deleted": len(items), "free": shutil.disk_usage(card.root).free}


# ─── The Summit app on the player ──────────────────────────────────────────

app_library = None  # set by api.py: () -> the phone app's library payload (server.Library.payload(lite=True))
art_file = None     # set by api.py: (summit id) -> a 256 px JPEG of its cover, or None


OTHER_FOLDERS = [{"id": -1, "name": "rekordbox"}, {"id": -2, "name": "iTunes"}]


def other_playlists(lib, playlists=None):
    """The rekordbox and Music app playlists as the app shows them: read-only (negative ids, kind "card"), filed in
    OTHER_FOLDERS, tracks as Summit ids. playlists: [(name, ids)] to include (default: every one)."""
    meta = {m["name"]: m for m in lib.meta}
    out, k = [], 0
    for name, ids in (lib.playlists if playlists is None else playlists):
        m = meta.get(name, {})
        if m.get("source") in SOURCE_FOLDERS:
            k += 1
            out.append({"id": -k, "name": m.get("label", name), "kind": "card", "ids": lib.summit_ids(ids), "version": 0,
                        "folder": SOURCE_FOLDERS[m["source"]], "source": m["source"]})
    return out


def write_app_library(card, lib, p, job):
    """Summit/library.json: the app's own library (tracks, map, Summit playlists, likes) plus a "card" section: where
    each track's file is on the card and the other sources' picked playlists. Summit/art/<id>.jpg: covers, written
    once each. Returns how many of Summit's tracks the app can play from the card."""
    if app_library is None:
        return 0
    job.step("Writing the Summit app's library…")
    payload = app_library()
    on_card = {r["rel"] for r in p["mirror"].values()}
    files = {sid: f"{MUSIC_DIR}/{rb.card_name(rel)}" for rel, sid in lib.sid_by_rel.items() if rel in on_card}
    others = other_playlists(lib, p["playlists"])
    payload["card"] = {"version": 1, "synced": int(time.time()), "files": files, "playlists": others, "folders": OTHER_FOLDERS}
    payload.pop("scores", None)
    payload.pop("tags", None)
    app_dir = os.path.join(card.root, APP_DIR)
    tmp = os.path.join(app_dir, "library.json.part")
    card.write_text(tmp, json.dumps(payload, separators=(",", ":"), ensure_ascii=False))
    os.replace(card.guard(tmp), card.guard(os.path.join(app_dir, "library.json")))

    if art_file is not None:
        art_dir = os.path.join(app_dir, "art")
        have = set(os.listdir(art_dir)) if os.path.isdir(art_dir) else set()
        todo = [sid for sid in files if f"{sid}.jpg" not in have]
        for i, sid in enumerate(todo, 1):
            job.step("Writing cover art for the Summit app", i, len(todo))
            try:
                src = art_file(sid)
                if src:
                    card.copy_in(str(src), os.path.join(art_dir, f"{sid}.jpg"))
            except OSError as e:
                job.note(f"FAIL art {sid}: {e}")
        for fn in have - {f"{sid}.jpg" for sid in files}:
            if fn.endswith(".jpg"):
                try:
                    card.unlink(os.path.join(art_dir, fn))
                except OSError:
                    pass
    job.note(f"Summit app: {len(files)} tracks playable from the card, {len(others)} rekordbox / Music playlists")
    return len(files)
