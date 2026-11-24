"""Summit's tags → rekordbox My Tags, written into rekordbox's own library through rbguard (the only door into rekordbox).

rekordbox's My Tag area has four categories of at most 50 tags each, so the axes map to Genre (44), Beat (beat
patterns plus Feel modifiers), Sound (Mood, Texture, Era, Edit type) and Components (Vocals, Lead sound). Summit owns My Tags entirely: a sync makes rekordbox's My Tags equal
Summit's plan, renaming the four category slots, deleting tags and links that aren't in the plan (including ones made
in rekordbox), and adding the missing ones. Nothing else in rekordbox is touched (never Rating or Colour).

What gets sent per track and axis: its confirmed tags (yours, or settled by known data), or, if the axis isn't settled,
predictions from a trained tag (>= MIN_TRAIN confirmations) scoring above LIKELY, up to the axis maximum. Zero-shot
scores are never sent: they're too weak to write into another app.

All access to rekordbox goes through rbguard: reads use a temporary copy, the write is a no-op while rekordbox is
locked in the app, and an unlocked write needs rekordbox closed and takes a backup first (data/rb_backups/).
"""

import secrets
import unicodedata
import uuid

from . import rbguard
from .taxonomy import AXES

CATS = {"Genre": ("Genre",), "Beat": ("Beat", "Feel"), "Sound": ("Mood", "Texture", "Era", "Edit"),
        "Components": ("Vocals", "Lead")}  # rekordbox ← Summit; each at most 50 tags
LIKELY = 0.8


def label(tag: str) -> str:
    """How a Summit tag reads in rekordbox: 'four-on-the-floor' → 'Four-on-the-floor', 'male vocal' → 'Male vocal'."""
    return tag[:1].upper() + tag[1:]


# ---- what to send ----
def plan(lib, trained: set[str]) -> dict[str, dict[str, list[tuple[str, str]]]]:
    """{track_id: {rekordbox category: [(tag, 'confirmed' | 'likely')]}} from the server's Library (lib.scores)."""
    cat_tags = lib.category_tags()
    confs, rejs = {}, {}
    for r in lib.con.execute("SELECT tt.track_id, g.name, tt.status FROM track_tags tt JOIN tags g ON g.id=tt.tag_id"):
        if r[0] in lib.idx and r[1] in lib.cats:
            (confs if r[2] == "confirmed" else rejs).setdefault(lib.idx[r[0]], set()).add(r[1])
    out = {}
    for i, tid in enumerate(lib.ids):
        conf = confs.get(i, set())
        rej = rejs.get(i, set())
        per = {}
        for rb, axes in CATS.items():
            got = []
            for axis in axes:
                tags = cat_tags.get(axis, [])
                c = [g for g in tags if g in conf]
                if c:
                    got += [(g, "confirmed") for g in c]
                    continue
                if axis == "Feel" and rej & set(tags):
                    continue  # feel was answered (as "none of these")
                cand = sorted((g for g in tags if g in trained and g not in rej and g in lib.scores
                               and lib.scores[g][i] > LIKELY), key=lambda g: -lib.scores[g][i])
                got += [(g, "likely") for g in cand[:AXES[axis][1] or len(cand)]]
            if got:
                per[rb] = got
        if per:
            out[tid] = per
    return out


def summary(p: dict, paths: dict[str, str], matched: set[str] | None) -> dict:
    """Counts for the app: tracks per category (confirmed / likely), tags per category, and rekordbox matching."""
    cats = {rb: {"confirmed": 0, "likely": 0, "tags": {}} for rb in CATS}
    for per in p.values():
        for rb, got in per.items():
            c = cats[rb]
            c["likely" if all(h == "likely" for _, h in got) else "confirmed"] += 1
            for g, _ in got:
                c["tags"][label(g)] = c["tags"].get(label(g), 0) + 1
    for c in cats.values():
        c["tags"] = sorted(c["tags"].items(), key=lambda x: -x[1])
    out = {"tracks": len(p), "cats": cats}
    if matched is not None:
        out["in_rekordbox"] = sum(1 for t in p if norm(paths.get(t, "")) in matched)
    return out


# ---- rekordbox (through rbguard) ----
def norm(path: str) -> str:
    return unicodedata.normalize("NFC", path)


def current() -> dict:
    """What rekordbox has now: its My Tag categories with tag counts, links, and the set of its track paths."""
    M = rbguard.models()
    with rbguard.snapshot() as db:
        rows = db.query(M.DjmdMyTag).all()
        cats = sorted((t for t in rows if t.ParentID == "root"), key=lambda t: t.Seq or 0)
        paths = {norm(c.FolderPath) for c in db.query(M.DjmdContent.FolderPath) if c.FolderPath}
        return {"cats": [{"name": c.Name, "tags": sum(1 for t in rows if t.ParentID == c.ID)} for c in cats],
                "links": db.query(M.DjmdSongMyTag).count(), "paths": paths}


def write(p: dict, paths: dict[str, str]) -> dict:
    """Make rekordbox's My Tags equal the plan (through rbguard: a no-op while rekordbox is locked in the app)."""
    return rbguard.write("sync My Tags", lambda db: _apply(db, p, paths))


def _apply(db, p: dict, paths: dict[str, str]) -> dict:
    M = rbguard.models()
    content = {norm(c.FolderPath): str(c.ID) for c in db.query(M.DjmdContent.ID, M.DjmdContent.FolderPath) if c.FolderPath}
    rows = db.query(M.DjmdMyTag).all()
    used = {t.ID for t in rows}

    def new_id() -> str:
        while True:
            i = str(secrets.randbits(32))
            if int(i) >= 100 and i not in used:
                used.add(i)
                return i

    stats = {"tags_added": 0, "tags_removed": 0, "links_added": 0, "links_removed": 0, "categories_renamed": 0}
    # Categories: reuse rekordbox's slots in order (renamed), so the four columns stay where they are.
    slots = sorted((t for t in rows if t.ParentID == "root"), key=lambda t: t.Seq or 0)
    cat_id = {}
    for n, name in enumerate(CATS):
        if n < len(slots):
            s = slots[n]
            if s.Name != name or s.Seq != n + 1:
                s.Name, s.Seq = name, n + 1
                stats["categories_renamed"] += 1
        else:
            s = M.DjmdMyTag.create(ID=str(n + 1) if str(n + 1) not in used else new_id(), Seq=n + 1, Name=name,
                                   Attribute=1, ParentID="root", UUID=str(uuid.uuid4()))
            used.add(s.ID)
            db.add(s)
        cat_id[name] = s.ID
    extra = slots[len(CATS):]
    # Tags: per category, the ones the plan uses, in taxonomy order.
    want = {rb: [] for rb in CATS}
    for rb, axes in CATS.items():
        names = [label(g) for a in axes for g in AXES[a][0]]
        seen = {label(g) for per in p.values() for g, _ in per.get(rb, [])}
        want[rb] = [g for g in names if g in seen]
    tag_id = {}
    links = db.query(M.DjmdSongMyTag).all()
    doomed = {s.ID for s in extra}
    for rb, names in want.items():
        have = {t.Name: t for t in rows if t.ParentID == cat_id[rb]}
        for name, t in have.items():
            if name not in names:
                doomed.add(t.ID)
        for seq, name in enumerate(names, 1):
            t = have.get(name)
            if t is None:
                t = M.DjmdMyTag.create(ID=new_id(), Seq=seq, Name=name, Attribute=0, ParentID=cat_id[rb], UUID=str(uuid.uuid4()))
                db.add(t)
                stats["tags_added"] += 1
            elif t.Seq != seq:
                t.Seq = seq
            tag_id[(rb, name)] = t.ID
    doomed |= {t.ID for t in rows if t.ParentID in doomed}  # children of a removed extra category
    # Links: the plan's (tag, track) pairs.
    target = set()
    for tid, per in p.items():
        cid = content.get(norm(paths.get(tid, "")))
        if cid:
            for rb, got in per.items():
                target |= {(tag_id[(rb, label(g))], cid) for g, _ in got}
    have_links = set()
    for s in links:
        key = (s.MyTagID, str(s.ContentID))
        if s.MyTagID in doomed or key not in target or key in have_links:
            db.delete(s)
            stats["links_removed"] += 1
        else:
            have_links.add(key)
    for t in rows:
        if t.ID in doomed:
            db.delete(t)
            stats["tags_removed"] += t.ParentID != "root"
    for my, cid in sorted(target - have_links):
        db.add(M.DjmdSongMyTag.create(ID=str(uuid.uuid4()), MyTagID=my, ContentID=cid, UUID=str(uuid.uuid4())))
        stats["links_added"] += 1
    stats["links"] = len(target)
    stats["tracks"] = len({c for _, c in target})
    changed = any(v for k, v in stats.items() if k not in ("links", "tracks"))
    return stats if changed else {**stats, "unchanged": True}
