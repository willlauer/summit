"""Known data → tags. `summit tags fetch` looks tracks up on Discogs and Last.fm (resumable, rate-limited, raw replies
kept in ext_meta); `summit tags resolve` turns file genres, those replies and the audio model's styles into evidence
and confirms what's well supported.

Remixes: a remix's genre is the remixer's, not the original's, so lookups search for the remix first and only fall
back to the original at low weight. DJ-pool version suffixes (Clean Extended, Radio Edit, …) are dropped.
"""

import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict

import numpy as np

from . import db
from .config import KEYS_FILE
from .taxonomy import (AXES, FAMILY, FILE_GENRE_MAP, GENRE_BEAT, GENRE_FEEL, GENRES, LASTFM_GENRE_EXTRA, LASTFM_MOOD,
                       STYLE_MAP)

UA = "Summit/0.1 (local music library tagger)"
KNOWN = ("file", "discogs", "lastfm")  # independent sources; the audio model only breaks ties
ORIGINAL_WEIGHT = 0.3  # a remix found only as its original: the original's genre is weak evidence

# ---------------------------------------------------------------- titles

_VERSION_WORDS = {"original", "extended", "radio", "club", "clean", "dirty", "short", "quick", "hit", "intro", "outro",
                  "main", "explicit", "album", "single", "version", "mix", "edit", "mixed", "full", "length", "length)",
                  "remastered", "remaster", "hq", "cdq", "vocal", "instrumental", "acapella", "acappella", "x", "&", "-", "/"}
_REMIX = re.compile(r"\b(remix|rmx|bootleg|flip|vip|rework|refix|re-edit|edit|transition|mashup|remode)\b", re.I)
_FEAT = re.compile(r"\s*[\(\[]?\b(feat\.?|ft\.?|featuring)\s+[^\)\]]*[\)\]]?", re.I)


def clean(artist: str, title: str) -> tuple[str, str, str | None]:
    """(main artist, bare title, remix credit or None)."""
    artist = _FEAT.sub("", artist or "").strip()
    artist = re.split(r",\s*| x | X ", artist)[0].strip()
    title = _FEAT.sub("", title or "")
    remix = None
    parts = re.findall(r"[\(\[]([^\)\]]*)[\)\]]", title)
    title = re.sub(r"\s*[\(\[][^\)\]]*[\)\]]", "", title)
    if " - " in title:  # "Finesse - Tall Boys 125-105 Transition"
        title, tail = title.split(" - ", 1)
        parts.append(tail)
    for p in parts:
        words = {w.lower() for w in re.findall(r"[\w&/\-]+", p) if not w.isdigit() and not re.fullmatch(r"[\d\-]+", w)}
        if words <= _VERSION_WORDS:
            continue  # a DJ-pool version, not a different track
        if _REMIX.search(p) and remix is None:
            remix = re.sub(r"\s+", " ", re.sub(r"\b\d+\s*-\s*\d+\b", "", p)).strip()
    return artist, re.sub(r"\.(wav|mp3|aiff?)\b.*$", "", title, flags=re.I).strip(), remix


def _version_parts(title: str) -> list[str]:
    parts = re.findall(r"[\(\[]([^\)\]]*)[\)\]]", title or "")
    bare = re.sub(r"\s*[\(\[][^\)\]]*[\)\]]", "", title or "")
    if " - " in bare:
        parts.append(bare.split(" - ", 1)[1])
    return [p.lower() for p in parts]


def edits_of(title: str) -> list[str]:
    """Edit-type tags from a title: what kind of version (remix, edit / bootleg, mashup, live, else original), plus
    'extended' for DJ-length versions. 'Radio Edit' / 'Clean Edit' are pool versions, not someone's edit."""
    kind, ext = None, False
    for p in _version_parts(title):
        words = set(re.findall(r"[\w&/\-]+", p))
        if re.search(r"\bmash ?-?up\b", p):
            kind = "mashup"
        elif re.search(r"\b(bootleg|re-?edit|rework|refix|flip|transition)\b", p) or ("edit" in words and words - _VERSION_WORDS - _POOL_WORDS):
            kind = kind or "edit / bootleg"
        elif re.search(r"\b(remix|rmx|vip|remode|dub)\b", p):
            kind = kind if kind in ("mashup", "edit / bootleg") else "remix"
        elif re.search(r"\blive\b", p):
            kind = kind or "live"
        if re.search(r"\b(extended|club mix|12\"|12 inch|intro)\b", p):
            ext = True
    return [kind or "original"] + (["extended"] if ext else [])


_POOL_WORDS = {"uk", "us", "censored", "edited", "dj", "pool", "short", "new", "alt", "alternate", "the", "a", "dirty", "clean"}
ERAS = ((1980, "pre-80s"), (1990, "80s"), (2000, "90s"), (2010, "2000s"), (2020, "2010s"), (9999, "2020s"))


def year_of(file_year: int | None, discogs: dict | None, remix: bool) -> int | None:
    """Release year: the earliest of the file's year and Discogs' exact matches (a DJ-pool edit of an old song keeps
    the song's year). A remix is its own release, so Discogs (which searched for the remix) wins over the file."""
    dy = []
    if discogs and discogs.get("match") == "exact":
        dy = [int(r["year"]) for r in discogs.get("results", []) if str(r.get("year") or "").isdigit() and 1920 <= int(r["year"]) <= 2049]
    if remix and dy:
        return min(dy)
    ys = dy + ([file_year] if file_year else [])
    return min(ys) if ys else None


def era_of(year: int | None) -> str | None:
    return next(e for y, e in ERAS if year < y) if year else None


def backfill_years(con, log=print) -> int:
    """Read the release year from files scanned before Summit kept it."""
    import mutagen

    from .scan import file_year

    rows = con.execute("SELECT id, path FROM tracks WHERE missing=0 AND year IS NULL").fetchall()
    for n, (tid, path) in enumerate(rows, 1):
        try:
            f = mutagen.File(path, easy=True)
            y = file_year(f) if f is not None else 0
        except Exception:
            y = 0
        con.execute("UPDATE tracks SET year=? WHERE id=?", (y, tid))
        if n % 500 == 0:
            con.commit()
            log(f"  years {n}/{len(rows)}")
    con.commit()
    return len(rows)


def _usable(artist: str, title: str) -> bool:
    return bool(artist and title) and artist.lower() not in {"unknown artist", "various artists", "unknown"}


# ---------------------------------------------------------------- HTTP


def _get(url: str, headers: dict | None = None, tries: int = 4) -> dict | None:
    for k in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(30 * (k + 1))
                continue
            if e.code == 404:
                return None
            if e.code >= 500:
                time.sleep(3 * (k + 1))
                continue
            raise
        except (urllib.error.URLError, TimeoutError):
            time.sleep(3 * (k + 1))
    return None


class _Pacer:
    def __init__(self, gap: float):
        self.gap, self.last = gap, 0.0

    def wait(self) -> None:
        d = self.last + self.gap - time.monotonic()
        if d > 0:
            time.sleep(d)
        self.last = time.monotonic()


# ---------------------------------------------------------------- Discogs

_discogs_pace = _Pacer(1.1)  # authenticated limit is 60/min


def _discogs_search(token: str, **params) -> list[dict]:
    _discogs_pace.wait()
    q = urllib.parse.urlencode({"type": "release", "per_page": 5, **params})
    r = _get(f"https://api.discogs.com/database/search?{q}", {"Authorization": f"Discogs token={token}"})
    return [{"title": x.get("title"), "year": x.get("year"), "genre": x.get("genre") or [], "style": x.get("style") or []}
            for x in (r or {}).get("results", [])]


def discogs_lookup(token: str, artist: str, title: str, remix: str | None) -> dict:
    if remix:
        res = _discogs_search(token, artist=artist, track=f"{title} {remix}")
        if not res:  # remixes are often filed under the remixer
            remixer = re.sub(_REMIX, "", remix).strip(" '’s")
            if remixer:
                res = _discogs_search(token, artist=remixer, track=title)
        if res:
            return {"match": "remix", "results": res}
        res = _discogs_search(token, artist=artist, track=title)
        return {"match": "original" if res else None, "results": res}
    res = _discogs_search(token, artist=artist, track=title)
    if not res:
        res = _discogs_search(token, q=f"{artist} {title}")
    return {"match": "exact" if res else None, "results": res}


# ---------------------------------------------------------------- Last.fm

_lastfm_pace = _Pacer(0.25)


def _lastfm(key: str, **params) -> list[dict]:
    _lastfm_pace.wait()
    q = urllib.parse.urlencode({"api_key": key, "format": "json", "autocorrect": 1, **params})
    r = _get(f"https://ws.audioscrobbler.com/2.0/?{q}") or {}
    tags = (r.get("toptags") or {}).get("tag") or []
    return [{"name": t["name"], "count": int(t.get("count", 0))} for t in tags[:30] if int(t.get("count", 0)) > 0]


def lastfm_lookup(key: str, artist: str, title: str, remix: str | None) -> dict:
    track = f"{title} ({remix})" if remix else title
    tags = _lastfm(key, method="track.gettoptags", artist=artist, track=track)
    if tags:
        return {"match": "remix" if remix else "exact", "tags": tags}
    # No track tags: the artist's tags say something about genre (never mood). For a remix that's the remixer.
    who = re.sub(_REMIX, "", remix).strip(" '’s") if remix else artist
    tags = _lastfm(key, method="artist.gettoptags", artist=who) if who else []
    return {"match": "artist" if tags else None, "tags": tags}


# ---------------------------------------------------------------- fetch


def load_keys() -> dict:
    try:
        return json.loads(KEYS_FILE.read_text())
    except (OSError, ValueError):
        return {}


def fetch(sources: list[str], limit: int | None = None, log=print) -> None:
    con = db.connect()
    keys = load_keys()
    lookups = {"discogs": discogs_lookup, "lastfm": lastfm_lookup}
    for src in sources:
        if not keys.get(src):
            log(f'{src}: no "{src}" key in {KEYS_FILE}, skipped')
            continue
        done = {r[0] for r in con.execute("SELECT track_id FROM ext_meta WHERE source=?", (src,))}
        todo = [r for r in con.execute("SELECT id, artist, title FROM tracks WHERE missing=0 ORDER BY id") if r["id"] not in done]
        todo = todo[:limit] if limit else todo
        log(f"{src}: {len(todo)} tracks to look up")
        found, t0 = 0, time.time()
        for n, r in enumerate(todo, 1):
            artist, title, remix = clean(r["artist"], r["title"])
            data = {"match": None, "skipped": "no artist/title"} if not _usable(artist, title) else \
                lookups[src](keys[src], artist, title, remix) | {"query": [artist, title, remix]}
            found += data["match"] is not None
            con.execute("INSERT OR REPLACE INTO ext_meta VALUES (?,?,?,?)", (r["id"], src, json.dumps(data), db.now()))
            con.commit()  # at once: an open write transaction would block the app's writes between slow lookups
            if n % 25 == 0 or n == len(todo):
                log(f"  {n}/{len(todo)} · {found} matched · {(time.time() - t0) / n:.1f}s/track")
        con.commit()


# ---------------------------------------------------------------- resolve


def _expand(v, w: float = 1.0) -> dict:
    """A map value → {genre or @family: weight}."""
    if v is None:
        return {}
    if isinstance(v, dict):
        return {g: w * x for g, x in v.items()}
    return {v: w}


def _style(full: str) -> dict:
    """Discogs "Parent---Style" (or a bare style) → {genre: weight}."""
    short = full.split("---")[-1]
    return _expand(STYLE_MAP.get(full, STYLE_MAP.get(short)))


def _norm(d: dict) -> dict:
    s = sum(d.values())
    return {k: v / s for k, v in d.items()} if s > 0 else {}


def _genre_name_map() -> dict:
    m = {g.lower(): g for g in FAMILY}
    m |= {k.split("---")[-1].lower(): v for k, v in STYLE_MAP.items()}
    m |= {k: v for k, v in FILE_GENRE_MAP.items() if v is not None}
    return m | LASTFM_GENRE_EXTRA


def discogs_evidence(data: dict) -> dict:
    if not data or not data.get("results"):
        return {}
    ev = defaultdict(float)
    for rank, res in enumerate(data["results"]):
        w = 1 / (rank + 1)
        for s in res["style"]:
            hits = {}
            for g in res["genre"]:
                hits |= _style(f"{g}---{s}")
            for g, x in (hits or _style(s)).items():
                ev[g] += w * x
    ev = _norm(ev)
    return {g: v * ORIGINAL_WEIGHT for g, v in ev.items()} if data["match"] == "original" else ev


def lastfm_evidence(data: dict, names: dict) -> tuple[dict, dict]:
    """(genre evidence, mood evidence) from Last.fm tags. Artist-level tags give genre only, at half weight."""
    if not data or not data.get("tags"):
        return {}, {}
    genre, mood = defaultdict(float), defaultdict(float)
    for t in data["tags"]:
        if t["count"] < 10:
            continue
        name, w = t["name"].lower().strip(), t["count"] / 100
        for g, x in _expand(names.get(name), w).items():
            genre[g] += x
        if name in LASTFM_MOOD and data["match"] != "artist":
            mood[LASTFM_MOOD[name]] = max(mood[LASTFM_MOOD[name]], w)
    genre = _norm(genre)
    if data["match"] == "artist" or data["match"] == "original":
        genre = {g: v * 0.5 for g, v in genre.items()}
    return genre, dict(mood)


def audio_genres(styles: np.ndarray, names: list[str]) -> dict:
    ev = defaultdict(float)
    for j in np.argsort(-styles)[:20]:
        for g, x in _style(names[j]).items():
            ev[g] += float(styles[j]) * x
    return _norm(ev)


def _families_to_genres(ev: dict, audio: dict) -> dict:
    """Family-level evidence ("@Rock") goes to that family's genres in proportion to the audio model's view."""
    out = defaultdict(float)
    for g, w in ev.items():
        if g.startswith("@"):
            fam = GENRES[g[1:]]
            share = _norm({x: audio.get(x, 0) + 1e-3 for x in fam})
            for x, s in share.items():
                out[x] += w * s * 0.6  # the audio did the narrowing, so it counts for less
        else:
            out[g] += w
    return dict(out)


def decide(known: dict[str, dict], audio: dict) -> tuple[str | None, float, bool]:
    """Main genre from known evidence, with the audio model as a tiebreak. → (genre, margin, confirm?)."""
    score = defaultdict(float)
    for ev in known.values():
        for g, w in ev.items():
            score[g] += w
    if not score:
        return None, 0.0, False
    top_audio = max(audio.values()) if audio else 1.0
    post = {g: s * (0.25 + audio.get(g, 0) / top_audio) for g, s in score.items()}
    top = max(post, key=post.get)
    margin = post[top] / sum(post.values())
    support = [src for src, ev in known.items() if ev and max(ev, key=ev.get) == top and ev[top] >= 0.3]
    audio_top3 = sorted(audio, key=audio.get, reverse=True)[:3]
    audio_ok = top in audio_top3 or (audio and FAMILY.get(max(audio, key=audio.get)) == FAMILY.get(top))
    strong = len(support) >= 2 or (len(support) == 1 and known[support[0]][top] >= 0.6 and margin >= 0.5 and audio_ok)
    return top, margin, strong


def resolve(log=print) -> dict:
    from .analyze import style_names

    con = db.connect()
    tags = {r["name"]: (r["id"], r["category"]) for r in con.execute("SELECT id, name, category FROM tags")}
    snames = style_names()
    names = _genre_name_map()
    user = defaultdict(lambda: defaultdict(set))  # track → category → {(tag, status)}
    for r in con.execute("SELECT tt.track_id, g.name, g.category, tt.status FROM track_tags tt JOIN tags g ON g.id=tt.tag_id WHERE tt.source='user'"):
        user[r[0]][r[2]].add((r[1], r[3]))
    ext = defaultdict(dict)
    for r in con.execute("SELECT track_id, source, data FROM ext_meta"):
        ext[r[0]][r[1]] = json.loads(r[2])
    styles = {r[0]: db.from_blob(r[1]) for r in con.execute("SELECT track_id, vec FROM vectors WHERE kind='styles'")}
    backfill_years(con, log)

    con.execute("DELETE FROM tag_evidence")
    con.execute("DELETE FROM track_tags WHERE source IN ('known', 'rule')")
    evidence, confirm = [], []
    stats = defaultdict(int)

    def blocked(tid, cat, tag):
        marks = user[tid][cat]
        return (tag, "rejected") in marks or any(s == "confirmed" for _, s in marks)  # user's call wins

    for r in con.execute("SELECT id, genre, artist, title, year FROM tracks WHERE missing=0").fetchall():
        tid = r["id"]
        # Facts, not judgements: era from the release year, edit type from the title. Confirmed unless you said otherwise.
        era = era_of(year_of(r["year"], ext[tid].get("discogs"), clean(r["artist"], r["title"])[2] is not None))
        for cat, got in (("Era", [era] if era else []), ("Edit", edits_of(r["title"]))):
            for t in got:
                evidence.append((tid, tags[t][0], "rule", 1.0))
                if not blocked(tid, cat, t):
                    confirm.append((tid, tags[t][0], "rule"))
                    stats[f"{cat.lower()}_confirmed"] += 1
        audio = audio_genres(styles[tid], snames) if tid in styles else {}
        file_ev = _expand(FILE_GENRE_MAP.get((r["genre"] or "").strip().lower()))
        lf_genre, lf_mood = lastfm_evidence(ext[tid].get("lastfm"), names)
        known = {"file": file_ev, "discogs": discogs_evidence(ext[tid].get("discogs")), "lastfm": lf_genre}
        known = {src: _families_to_genres(ev, audio) for src, ev in known.items()}
        for src, ev in known.items():
            stats[f"has_{src}"] += bool(ev)
            evidence += [(tid, tags[g][0], src, round(w, 3)) for g, w in ev.items() if g in tags and w >= 0.05]
        evidence += [(tid, tags[m][0], "lastfm", round(w, 3)) for m, w in lf_mood.items()]

        top, margin, strong = decide(known, audio)
        if top is None:
            stats["genre_none"] += 1
            continue
        if strong and not blocked(tid, "Genre", top):
            confirm.append((tid, tags[top][0], "known"))
            stats["genre_confirmed"] += 1
        else:
            stats["genre_suggested"] += 1
        # Beat and feel follow the main genre: certain rules confirm when the genre is confirmed, the rest suggest.
        k = 1.0 if strong else 0.5 * margin
        for cat, rules in (("Beat", GENRE_BEAT), ("Feel", GENRE_FEEL)):
            for t, w in rules.get(top, {}).items():
                evidence.append((tid, tags[t][0], "rule", round(w * k, 3)))
                if strong and w >= 1.0 and cat == "Beat" and not blocked(tid, cat, t):
                    confirm.append((tid, tags[t][0], "rule"))
                    stats["beat_confirmed"] += 1

    con.executemany("INSERT OR REPLACE INTO tag_evidence VALUES (?,?,?,?)", evidence)
    now = db.now()
    con.executemany("INSERT OR IGNORE INTO track_tags VALUES (?,?,'confirmed',?,?)", [(t, g, s, now) for t, g, s in confirm])
    con.commit()
    log(" · ".join(f"{k} {v}" for k, v in sorted(stats.items())))
    return dict(stats)


def status(con) -> str:
    q = lambda s, *a: con.execute(s, a).fetchone()[0]
    lines = []
    for src in ("discogs", "lastfm"):
        n = q("SELECT COUNT(*) FROM ext_meta WHERE source=?", src)
        m = q("SELECT COUNT(*) FROM ext_meta WHERE source=? AND json_extract(data, '$.match') IS NOT NULL", src)
        lines.append(f"{src}: looked up {n} · matched {m}")
    for cat in AXES:
        rows = con.execute("SELECT tt.source, COUNT(DISTINCT tt.track_id) FROM track_tags tt JOIN tags g ON g.id=tt.tag_id "
                           "WHERE g.category=? AND tt.status='confirmed' GROUP BY tt.source", (cat,)).fetchall()
        lines.append(f"{cat}: " + (" · ".join(f"{s} {n}" for s, n in rows) or "none confirmed"))
    return "\n".join(lines)


# ---------------------------------------------------------------- the app's view (Settings › Tags, "Why?")


def save_keys(new: dict) -> dict:
    """Test each given key with one request and save the ones that work to data/keys.json ("" removes a key), so a
    mistyped key never replaces a working one. → {source: {"ok": …, "error": …}}."""
    from .config import KEYS_FILE

    keys, out = load_keys(), {}
    for src, v in new.items():
        if src not in ("discogs", "lastfm") or v is None:
            continue
        v = v.strip()
        if not v:
            keys.pop(src, None)
            out[src] = {"ok": True, "removed": True}
            continue
        out[src] = test_key(src, v)
        if out[src]["ok"]:
            keys[src] = v
    KEYS_FILE.parent.mkdir(parents=True, exist_ok=True)
    KEYS_FILE.write_text(json.dumps(keys, indent=2) + "\n")
    KEYS_FILE.chmod(0o600)
    return out


def test_key(src: str, key: str) -> dict:
    try:
        if src == "discogs":
            _discogs_search(key, artist="Daft Punk", track="Digital Love")
        else:
            q = urllib.parse.urlencode({"api_key": key, "format": "json", "method": "artist.gettoptags", "artist": "Daft Punk"})
            req = urllib.request.Request(f"https://ws.audioscrobbler.com/2.0/?{q}", headers={"User-Agent": UA})
            urllib.request.urlopen(req, timeout=15).read()
        return {"ok": True}
    except urllib.error.HTTPError as e:
        return {"ok": False, "error": f"HTTP {e.code}" + (" (invalid key)" if e.code in (401, 403) else "")}
    except OSError as e:
        return {"ok": False, "error": str(e)}


def info(con) -> dict:
    """Settings › Tags: keys present (never their values), lookup coverage, and confirmed tags per axis by source."""
    q = lambda s, *a: con.execute(s, a).fetchone()[0]
    total = q("SELECT COUNT(*) FROM tracks WHERE missing=0")
    keys = load_keys()
    lookups = {}
    for src in ("discogs", "lastfm"):
        rows = dict(con.execute("SELECT COALESCE(json_extract(e.data, '$.match'), 'none'), COUNT(*) FROM ext_meta e "
                                "JOIN tracks t ON t.id=e.track_id AND t.missing=0 WHERE e.source=? GROUP BY 1", (src,)).fetchall())
        done = sum(rows.values())
        lookups[src] = {"key": bool(keys.get(src)), "done": done, "pending": total - done, "matches": rows}
    axes = {}
    for cat in AXES:
        rows = con.execute("SELECT tt.source, COUNT(DISTINCT tt.track_id) FROM track_tags tt JOIN tags g ON g.id=tt.tag_id "
                           "WHERE g.category=? AND tt.status='confirmed' GROUP BY tt.source", (cat,)).fetchall()
        axes[cat] = dict(rows)
    return {"total": total, "lookups": lookups, "axes": axes}


def explain(con, track_id: str) -> dict:
    """Everything behind a track's tags: what each source said, what that mapped to, and the marks."""
    from .analyze import style_names

    r = con.execute("SELECT artist, title, genre FROM tracks WHERE id=?", (track_id,)).fetchone()
    ext = {s: json.loads(d) for s, d in con.execute("SELECT source, data FROM ext_meta WHERE track_id=?", (track_id,))}
    v = con.execute("SELECT vec FROM vectors WHERE track_id=? AND kind='styles'", (track_id,)).fetchone()
    names = style_names()
    styles = db.from_blob(v[0]) if v else None
    audio = audio_genres(styles, names) if styles is not None else {}
    evidence = defaultdict(dict)
    for e in con.execute("SELECT g.name, g.category, e.source, e.weight FROM tag_evidence e JOIN tags g ON g.id=e.tag_id "
                         "WHERE e.track_id=? ORDER BY e.weight DESC", (track_id,)):
        evidence[e[0]] |= {"cat": e[1], e[2]: round(e[3], 2)}
    for g, w in audio.items():
        if w >= 0.05:
            evidence[g] = evidence.get(g, {"cat": "Genre"}) | {"audio": round(w, 2)}
    marks = [dict(m) for m in con.execute(
        "SELECT g.name AS tag, g.category AS cat, tt.status, tt.source FROM track_tags tt JOIN tags g ON g.id=tt.tag_id "
        "WHERE tt.track_id=? ORDER BY g.category", (track_id,))]
    artist, title, remix = clean(r["artist"], r["title"])
    fg = (r["genre"] or "").strip()
    return {
        "query": {"artist": artist, "title": title, "remix": remix},
        "file": {"genre": fg, "maps": FILE_GENRE_MAP.get(fg.lower(), "unmapped") if fg else None},
        "discogs": ext.get("discogs"), "lastfm": ext.get("lastfm"),
        "audio": [[names[j].replace("---", " › "), round(float(styles[j]), 2)] for j in np.argsort(-styles)[:6]] if styles is not None else [],
        "evidence": dict(evidence), "marks": marks,
    }
