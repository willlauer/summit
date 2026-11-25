"""Step 5 backend: FastAPI over the SQLite store. Serves the library, audio (Range-capable), search, tags,
clusters, playlists, vibes and export. The desktop UI lives in web/ (same origin); the Android app (android/) uses the
same API, plus /stream (phone-friendly audio) and /art (cover art)."""

import json
from pathlib import Path
import subprocess
import threading
import time

import numpy as np
from fastapi import Body, Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from . import clap_text, db, lan, rekordbox
from .config import DEFAULT_SPACE, MAX_DURATION, ROOT, SPACES, TAG_SPACE, WEB
from .export import export_all
from .layout import load, space_matrix, unit
from .media import TYPES, Prefetch, art_file, stream_file
from .pipeline import Job
from .scan import pending
from .sync import api as sync_api
from .tags import MIN_TRAIN, trained, zero_shot


class Library:
    """In-memory view of everything the UI needs; reloaded after pipeline runs."""

    def __init__(self):
        self.lock = threading.RLock()
        self.con = db.connect()
        self.reload()

    def reload(self) -> None:
        from .analyze import style_names

        with self.lock:
            con = self.con
            laid = {r[0] for r in con.execute("SELECT DISTINCT track_id FROM layouts")}
            ids = [r[0] for r in con.execute(
                "SELECT t.id FROM tracks t JOIN vectors v ON v.track_id=t.id AND v.kind='clap' WHERE t.missing=0 AND t.duration <= ? ORDER BY t.id",
                (MAX_DURATION,))]
            self.ids = [i for i in ids if i in laid] if laid else ids
            self.idx = {i: k for k, i in enumerate(self.ids)}
            self.rows = {r["id"]: dict(r) for r in con.execute("SELECT * FROM tracks")}
            self.cats = {r[0]: r[1] for r in con.execute("SELECT name, category FROM tags")}
            self.load_rekordbox()
            if not self.ids:
                self.C = self.X = np.zeros((0, 1))
                self.styles, self.scores = [], {}
                return
            self.C = unit(load(con, "clap", self.ids))  # text search lives in CLAP space
            self.M = {s: space_matrix(con, s, self.ids) for s in SPACES}
            self.X = self.M[TAG_SPACE]
            names = [n.split("---")[-1] for n in style_names()]
            S = load(con, "styles", self.ids)
            self.styles = [[[names[j], round(float(s[j]), 2)] for j in np.argsort(-s)[:3]] for s in S]
            self.zs = zero_shot(con, self.C)
            self.scores = {t: self.score(t) for t in self.tag_names()}

    def load_rekordbox(self) -> None:
        """rekordbox's BPM per track (from the last collection import)."""
        with self.lock:
            self.rb = {}
            for r in self.con.execute("SELECT * FROM rb_tracks"):
                a = json.loads(r["attrs"])
                self.rb[r["track_id"]] = {"bpm": float(a.get("AverageBpm") or 0)}

    def category_tags(self) -> dict[str, list[str]]:
        from .taxonomy import AXES

        out = {}
        for r in self.con.execute("SELECT name, category FROM tags ORDER BY id"):
            out.setdefault(r[1], []).append(r[0])
        return {c: out.get(c, []) for c in AXES}

    def tag_names(self) -> list[str]:
        return [r[0] for r in self.con.execute("SELECT name FROM tags ORDER BY id")]

    def marks(self, tag: str) -> tuple[np.ndarray, np.ndarray]:
        rows = self.con.execute(
            "SELECT tt.track_id, tt.status FROM track_tags tt JOIN tags g ON g.id=tt.tag_id WHERE g.name=?", (tag,)).fetchall()
        pos = [self.idx[r[0]] for r in rows if r[1] == "confirmed" and r[0] in self.idx]
        neg = [self.idx[r[0]] for r in rows if r[1] == "rejected" and r[0] in self.idx]
        return np.array(pos, dtype=int), np.array(neg, dtype=int)

    def score(self, tag: str) -> np.ndarray:
        from .taxonomy import RULE_ONLY

        if self.cats.get(tag) in RULE_ONLY:  # edit type comes from the title only: nothing to predict
            return np.zeros(len(self.ids))
        pos, neg = self.marks(tag)
        if len(pos) >= MIN_TRAIN:
            return trained(self.X, pos, neg)
        if tag in self.zs:
            return self.zs[tag]
        if len(pos):  # a small new vibe: similarity to its examples' centroid, squashed like the zero-shot scores
            sims = self.X @ unit(self.X[pos].mean(0, keepdims=True))[0]
            z = (sims - sims.mean()) / max(sims.std(), 1e-6)
            return 1 / (1 + np.exp(-1.6 * (z - 1.0)))
        return np.zeros(len(self.ids))

    def payload(self, lite: bool = False) -> dict:
        """lite (the phone): no tag scores or Discogs styles, which are most of the size."""
        with self.lock:
            con = self.con
            tags = {}
            for r in con.execute("SELECT g.name, tt.track_id, tt.status, tt.source FROM track_tags tt JOIN tags g ON g.id=tt.tag_id"):
                t = tags.setdefault(r["track_id"], {"confirmed": [], "rejected": [], "auto": []})
                t[r["status"]].append(r["name"])
                if r["status"] == "confirmed" and r["source"] != "user":
                    t["auto"].append(r["name"])  # confirmed by known data (file / Discogs / Last.fm), not by you
            liked = [r[0] for r in con.execute("SELECT track_id FROM likes ORDER BY created_at DESC")]
            liked_set = set(liked)
            tracks = []
            for k, i in enumerate(self.ids):
                r = self.rows[i]
                rbpm = self.rb.get(i, {}).get("bpm")  # rekordbox's BPM where it has one, so the two apps agree
                tracks.append({
                    "id": i, "artist": r["artist"], "title": r["title"], "album": r["album"], "dur": round(r["duration"] or 0),
                    "ext": r["format"], "bpm": round(rbpm) if rbpm else round(r["bpm"]) if r["bpm"] else None,
                    "key": r["key"], "audition": r["audition"] or 0,
                    "liked": i in liked_set, "added": round(r["added_at"] or 0), "no": track_no(r["path"]),
                    **({} if lite else {"styles": self.styles[k], **tags.get(i, {"confirmed": [], "rejected": [], "auto": []})}),
                })
            spaces, clusters, families = {}, {}, {}
            group = lambda r, key: {"id": r[key], "label": r["label"], "src": r["label_source"],
                                    "styles": json.loads(r["styles"] or "[]"), "vibes": json.loads(r["vibes"] or "[]")}
            for s in SPACES:
                pts = {r["track_id"]: r for r in con.execute("SELECT * FROM layouts WHERE space=?", (s,))}
                if pts and all(i in pts for i in self.ids):
                    spaces[s] = {
                        "x": [round(pts[i]["x"], 3) for i in self.ids], "y": [round(pts[i]["y"], 3) for i in self.ids],
                        "c": [pts[i]["cluster"] for i in self.ids], "f": [pts[i]["family"] for i in self.ids],
                    }
                    p3 = {r["track_id"]: r for r in con.execute("SELECT * FROM layouts3d WHERE space=?", (s,))}
                    if p3 and all(i in p3 for i in self.ids):
                        spaces[s]["xyz"] = [[round(p3[i][a], 3) for i in self.ids] for a in "xyz"]
                    clusters[s] = [group(r, "cluster") | {"family": r["family"]}
                                   for r in con.execute("SELECT * FROM clusters WHERE space=? ORDER BY cluster", (s,))]
                    families[s] = [group(r, "family") for r in con.execute("SELECT * FROM families WHERE space=? ORDER BY family", (s,))]
            return {
                "tracks": tracks, "spaces": spaces, "clusters": clusters, "families": families,
                "spaceList": [{"key": k, "label": v["label"]} for k, v in SPACES.items() if k in spaces], "defaultSpace": DEFAULT_SPACE,
                "tags": [dict(r) for r in con.execute("SELECT name, category, parent FROM tags ORDER BY id")],
                "scores": {} if lite else {t: np.round(v, 2).tolist() for t, v in self.scores.items()},
                "playlists": self.playlists(), "folders": self.folders(), "liked": [i for i in liked if i in self.idx],
                "rekordbox": rekordbox.status(con),
            }

    def playlists(self) -> list[dict]:
        out = []
        for p in self.con.execute("SELECT * FROM playlists ORDER BY created_at"):
            ids = [r[0] for r in self.con.execute("SELECT track_id FROM playlist_tracks WHERE playlist_id=? ORDER BY position", (p["id"],))]
            out.append({"id": p["id"], "name": p["name"], "kind": p["kind"], "data": json.loads(p["data"] or "null"), "ids": ids,
                        "version": p["version"] or 0, "updated": p["updated_at"], "folder": p["folder_id"]})
        return out

    def folders(self) -> list[dict]:
        return [{"id": f["id"], "name": f["name"], "parent": f["parent_id"]}
                for f in self.con.execute("SELECT * FROM folders ORDER BY name COLLATE NOCASE")]


def git_version() -> str:
    """The code this server is running, in the same form as the phone app's version: 0.1.<commits> (<hash>[-dirty])."""
    git = lambda *a: subprocess.run(["git", *a], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    try:
        return f"0.1.{git('rev-list', '--count', 'HEAD')} ({git('describe', '--always', '--dirty')})"
    except OSError:
        return "unknown"


VERSION = git_version()
app = FastAPI(title="Summit")
lib: Library | None = None
def _after_update() -> None:
    lib.reload()
    stream_job.start()  # new tracks get their phone copies right away


job = Job(on_done=_after_update)
stream_job = Job(on_done=lambda: None, args=("transcode",), first_stage="stream")  # Tools › Phone streaming
tag_job = Job(on_done=lambda: lib.reload(), args=("tags", "update"), first_stage="discogs")  # Tools › Tags
prefetch = Prefetch()


class GzipApi:
    """Compress JSON (the library is ~1.3 MB, for the phone over cellular); never audio, which is served by Range."""

    def __init__(self, app):
        from starlette.middleware.gzip import GZipMiddleware

        self.app, self.gzip = app, GZipMiddleware(app, minimum_size=2048)

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http" and scope["path"].startswith("/api/"):
            return await self.gzip(scope, receive, send)
        return await self.app(scope, receive, send)


app.add_middleware(GzipApi)


def track_no(path: str) -> int | None:
    """Album order from the file name, as Music.app names files: "01 Title" -> 1, "2-03 Title" (disc 2) -> 203."""
    import re

    m = re.match(r"(?:(\d{1,2})-)?(\d{1,3})[ ._-]", Path(path).name)
    return (int(m.group(1) or 0) * 100 + int(m.group(2))) if m else None


def sync_source() -> dict:
    """Summit's side of device sync (sync/library.py): every playlist with its folder path, Liked, and their files."""
    with lib.lock:
        folders = {f["id"]: f for f in lib.folders()}

        def path(fid):
            out, seen = [], set()
            while fid in folders and fid not in seen:
                seen.add(fid)
                out.insert(0, folders[fid]["name"])
                fid = folders[fid]["parent"]
            return out

        pls = [{"id": p["id"], "name": p["name"], "folder_path": path(p["folder"]), "ids": p["ids"]} for p in lib.playlists()]
        liked = [r[0] for r in lib.con.execute("SELECT track_id FROM likes ORDER BY created_at DESC")]
        tracks = {i: {k: lib.rows[i][k] for k in ("path", "artist", "title", "duration", "size")} for i in lib.ids if i in lib.rows}
    return {"tracks": tracks, "playlists": pls, "liked": liked}


sync_api.source = sync_source


@app.get("/api/extplaylists")
def ext_playlists():
    """The rekordbox and Music app playlists in the app's format (read-only), for Wi-Fi sync to the phone / JM21."""
    from .sync import fiio

    try:
        sl = sync_api.library()
    except HTTPException:
        return {"playlists": [], "folders": fiio.OTHER_FOLDERS}
    return {"playlists": fiio.other_playlists(sl), "folders": fiio.OTHER_FOLDERS}


@app.get("/api/lan/info", dependencies=[Depends(sync_api.local_only)])
def lan_info():
    a = apk_info()
    return lan.info() | {"apk": {k: v for k, v in a.items() if k not in ("file", "dm")} if a else None}


@app.post("/api/lan/settings", dependencies=[Depends(sync_api.local_only)])
def lan_settings(enabled: bool | None = Body(None), new_code: bool = Body(False)):
    return lan.settings(enabled, new_code)


@app.delete("/api/lan/devices/{device_id}", dependencies=[Depends(sync_api.local_only)])
def lan_forget(device_id: str):
    return lan.forget(device_id)


APK_DIRS = [ROOT / "android" / "app" / "build" / "outputs" / "apk" / v for v in ("release", "debug")]


def apk_info() -> dict | None:
    """The newest Android build on this Mac (what devices update to over Wi-Fi), from the build's own metadata.
    Release builds (fast, what devices should run) and debug builds both count; the newer one wins."""
    best = None
    for d in APK_DIRS:
        try:
            meta = json.loads((d / "output-metadata.json").read_text())["elements"][0]
            f = d / meta["outputFile"]
            # the compile profile for Android 12+ (Android compiles the app with it at install: no interpreter lag)
            dm = next((d / p for e in json.loads((d / "output-metadata.json").read_text()).get("baselineProfiles", [])
                       if e.get("minApi", 0) >= 31 for p in e.get("baselineProfiles", [])), None)
            info = {"versionCode": meta["versionCode"], "versionName": meta["versionName"], "bytes": f.stat().st_size,
                    "built": f.stat().st_mtime, "file": f, "dm": dm if dm and dm.exists() else None}
        except (OSError, ValueError, KeyError, IndexError):
            continue
        if best is None or info["versionCode"] > best["versionCode"]:
            best = info
    return best


@app.get("/api/app/version")
def app_version():
    a = apk_info()
    return {k: v for k, v in a.items() if k not in ("file", "dm")} | {"profile": bool(a["dm"])} if a else {"versionCode": 0}


@app.get("/api/app/apk")
def app_apk():
    a = apk_info()
    if not a:
        raise HTTPException(404, "no Android build on this Mac")
    return FileResponse(a["file"], media_type="application/vnd.android.package-archive", filename="summit.apk")


@app.get("/api/app/profile")
def app_profile():
    a = apk_info()
    if not a or not a["dm"]:
        raise HTTPException(404, "no compile profile")
    return FileResponse(a["dm"], media_type="application/octet-stream", filename="summit.dm")


@app.get("/api/lan/hello")
def lan_hello():
    return {"summit": True, "name": lan.mac_name(), "version": VERSION}


@app.post("/api/lan/pair")
def lan_pair(code: str = Body(...), name: str = Body("device")):
    token = lan.pair(code, name)
    if not token:
        raise HTTPException(403, "Wrong code (see Settings › Wi-Fi sync on the Mac)")
    return {"token": token, "name": lan.mac_name()}
sync_api.fiio.app_library = lambda: lib.payload(lite=True)  # the phone app's library, written to the card for the JM21 app
sync_api.fiio.art_file = lambda tid: art_file(lib.rows[tid], 256) if tid in lib.rows else None
app.include_router(sync_api.router)


@app.middleware("http")
async def revalidate(request: Request, call_next):
    """The WebKit window caches aggressively; make it revalidate the UI files so updates show up on reload."""
    resp = await call_next(request)
    if request.url.path == "/" or request.url.path.startswith("/web/"):
        resp.headers["Cache-Control"] = "no-cache"
    return resp


@app.on_event("startup")
def _startup() -> None:
    global lib
    from . import rbguard

    rbguard.install()  # nothing in this process may write into rekordbox's folders except through rbguard
    lib = Library()
    clap_text.warm()
    from . import streaming
    from .media import coverage

    streaming.apply()  # keep the Mac awake while serving, if on
    try:
        lan.start(app)  # Wi-Fi sync listener for the phone / JM21 (lan.py)
    except Exception as e:
        print("Wi-Fi sync listener failed:", e)
    c = coverage(lib.con)
    if any(v["ready"] < v["total"] for v in c.values()):
        stream_job.start()  # finish the phone copies in the background


@app.get("/")
def index():
    return FileResponse(WEB / "index.html")


@app.get("/api/version")
def version():
    return {"version": VERSION}


@app.get("/api/library")
def library(lite: bool = False):
    return JSONResponse(lib.payload(lite))


@app.get("/audio/{track_id}")
def audio(track_id: str):
    r = lib.rows.get(track_id)
    if not r:
        raise HTTPException(404)
    return FileResponse(r["path"], media_type=TYPES.get(r["format"]))  # Starlette FileResponse handles Range


@app.get("/stream/{track_id}")
def stream(track_id: str, q: str = "high"):
    """Phone audio (media.py): q=high (lossless as 256k AAC, lossy as is) or q=low (128k AAC). Normally made ahead of
    time; a track that isn't ready yet is converted now (a couple of seconds)."""
    r = lib.rows.get(track_id)
    if not r:
        raise HTTPException(404)
    try:
        path, media = stream_file(r, q)
    except Exception as e:
        raise HTTPException(500, f"transcode failed: {e}")
    return FileResponse(path, media_type=media)


@app.post("/api/prefetch")
def prefetch_tracks(ids: list[str] = Body(...), q: str = Body("high")):
    """Transcode these (the next tracks in a queue) in the background."""
    prefetch.add([lib.rows[i] for i in ids[:20] if i in lib.rows], q if q in ("high", "low") else "high")
    return {"ok": True}


@app.get("/art/{track_id}")
def art(track_id: str, s: int = 256):
    r = lib.rows.get(track_id)
    path = art_file(r, s) if r else None
    if not path:
        raise HTTPException(404)
    return FileResponse(path, media_type="image/jpeg", headers={"Cache-Control": "public, max-age=604800"})


def match_tags(q: str) -> tuple[list[str], str]:
    """Tags named in a query (longest phrase first, whole words), and the text left over."""
    import re

    from .taxonomy import search_phrases

    rest, found = f" {q.lower()} ", []
    for phrase, tag in sorted(search_phrases().items(), key=lambda kv: -len(kv[0])):
        pat = re.compile(rf"(?<![\w&-]){re.escape(phrase)}(?![\w&-])")
        if pat.search(rest):
            rest = pat.sub(" ", rest)
            if tag not in found:
                found.append(tag)
    return found, " ".join(rest.split())


def tag_match_scores(tags: list[str]) -> np.ndarray:
    """How well each track fits all of the tags: confirmed = 1, rejected = 0, else its score; the weakest tag counts."""
    cols = []
    for t in tags:
        s = lib.scores.get(t, np.zeros(len(lib.ids))).copy()
        pos, neg = lib.marks(t)
        s[pos], s[neg] = 1.0, 0.0
        cols.append(s)
    return np.min(cols, axis=0)


@app.get("/api/search")
def search(q: str, n: int = 60, mode: str = "both"):
    """mode: both (name matches first, then by sound) | vibe (by sound only) | name (title / artist matches only).
    Tags named in the query (taxonomy.search_phrases) lift tracks that have them; each result lists them."""
    q = q.strip()
    n = max(1, min(n, 2000))
    if not q or not lib.ids:
        return []
    ql = q.lower()
    hits = [k for k, i in enumerate(lib.ids) if ql in f"{lib.rows[i]['artist']} {lib.rows[i]['title']}".lower()]
    if mode == "name" or not clap_text.ready():
        return [{"i": k, "score": 1.0} for k in hits[:n]]
    tags, rest = match_tags(q)
    sims = lib.C @ clap_text.embed([rest or q], cache=False)[0]
    if tags:
        sims = sims + 0.6 * tag_match_scores(tags)  # CLAP similarities span ~0.3, so a confirmed tag leads
    if mode != "vibe":
        sims[hits] += 1.0  # name matches first
    top = np.argsort(-sims)[:n]
    lo, hi = float(sims[top[-1]]), float(sims[top[0]])
    return [{"i": int(k), "score": round((float(sims[k]) - lo) / max(hi - lo, 1e-6), 3), **({"tags": tags} if tags else {})} for k in top]


@app.get("/api/search/tags")
def search_tags(q: str):
    """Which tags a query names (the Search page shows them), and how many tracks have them confirmed / likely."""
    tags, rest = match_tags(q.strip())
    if not tags or not lib.ids:
        return {"tags": tags, "rest": rest, "confirmed": 0, "likely": 0}
    s = tag_match_scores(tags)
    return {"tags": tags, "rest": rest, "confirmed": int((s >= 1.0).sum()), "likely": int(((s >= 0.6) & (s < 1.0)).sum())}


@app.get("/api/neighbors")
def neighbors(i: int, k: int = 10, space: str = "combined"):
    """Most similar tracks by cosine in the full embedding space (not 2D map distance)."""
    M = lib.M.get(space, lib.X)
    sims = M @ M[i]
    sims[i] = -2
    return [int(j) for j in np.argsort(-sims)[:k]]


@app.post("/api/plays")
def add_play(id: str = Body(...), client: str = Body("desktop"), secs: float = Body(0)):
    """A real listen (the player decides: 30 s heard, or half a short track)."""
    if id not in lib.rows:
        raise HTTPException(404)
    with lib.lock:
        lib.con.execute("INSERT INTO plays VALUES (?,?,?,?)", (id, time.time(), client, round(secs, 1)))
        lib.con.commit()
    return {"ok": True}


DISCOVER_DAYS = 30
HALF_LIFE = 7 * 86400  # recent plays count for more


def _spread(pool: list[int], weights: np.ndarray, groups: list[int], n: int, rng) -> list[int]:
    """n picks from pool, weighted, taking one per group in turn so a single region can't fill the list."""
    by: dict[int, list[int]] = {}
    order = rng.choice(len(pool), size=len(pool), replace=False, p=weights / weights.sum()) if len(pool) else []
    for k in order:
        by.setdefault(groups[k], []).append(pool[k])
    lanes = list(by.values())
    rng.shuffle(lanes)
    out = []
    while len(out) < n and any(lanes):
        for lane in lanes:
            if lane and len(out) < n:
                out.append(lane.pop(0))
    rng.shuffle(out)
    return out


@app.post("/api/discover")
def discover(space: str = Body(DEFAULT_SPACE), n: int = Body(20), exclude: list[int] = Body([])):
    """Two fresh samplings around what you've been playing (the last 30 days, recent plays weighted up):
    far: tracks least like anything you've played, spread across clusters;
    near: tracks close to it that you've never played (or liked). Without any plays yet, Liked stands in."""
    n = max(1, min(n, 100))
    with lib.lock:
        if not lib.ids:
            return {"near": [], "far": [], "basis": None}
        now = time.time()
        rows = lib.con.execute("SELECT track_id, at FROM plays WHERE at > ? ORDER BY at DESC LIMIT 500", (now - DISCOVER_DAYS * 86400,)).fetchall()
        basis, w = "plays", {}
        for tid, at in rows:
            if tid in lib.idx:
                w[lib.idx[tid]] = w.get(lib.idx[tid], 0) + 0.5 ** ((now - at) / HALF_LIFE)
        liked = [lib.idx[r[0]] for r in lib.con.execute("SELECT track_id FROM likes") if r[0] in lib.idx]
        if not w:
            basis, w = "liked", {k: 1.0 for k in liked}
        if not w:
            return {"near": [], "far": [], "basis": None}
        played = {lib.idx[r[0]] for r in lib.con.execute("SELECT DISTINCT track_id FROM plays") if r[0] in lib.idx}
        M = lib.M.get(space, lib.X)
        cluster_of = dict(lib.con.execute("SELECT track_id, cluster FROM layouts WHERE space=?", (space,)).fetchall())
    seeds = np.array(list(w))
    sw = np.array([w[k] for k in seeds])
    # How close each track is to the recent listening: its best weighted cosine to any recent track.
    S = M @ M[seeds].T
    close = (S * (0.5 + 0.5 * sw / sw.max())).max(axis=1)
    groups = [cluster_of.get(i, 0) for i in lib.ids]
    rng = np.random.default_rng()
    skip = set(exclude) | set(seeds.tolist())
    N = len(lib.ids)
    rank = np.argsort(close)  # least like recent listening first
    far_pool = [int(k) for k in rank[: max(n * 6, N // 8)] if k not in skip] or [int(k) for k in rank[: n * 6] if k not in set(seeds.tolist())]
    far_w = np.array([1.0 - close[k] for k in far_pool]) + 1e-3
    known = played | set(liked) | set(seeds.tolist())
    near_rank = [int(k) for k in rank[::-1] if k not in known]
    near_pool = [k for k in near_rank[: max(n * 6, N // 10)] if k not in skip] or near_rank[: n * 6]
    near_w = np.array([close[k] for k in near_pool]) ** 4 + 1e-3
    return {
        "far": _spread(far_pool, far_w, [groups[k] for k in far_pool], n, rng),
        "near": _spread(near_pool, near_w, [groups[k] for k in near_pool], n, rng),
        "basis": {"kind": basis, "tracks": [int(k) for k in seeds[np.argsort(-sw)][:200]], "plays": len(rows), "days": DISCOVER_DAYS},
    }


@app.post("/api/suggest")
def suggest(ids: list[str] = Body(...), k: int = Body(20), space: str = Body("combined")):
    """Tracks that fit a set (a playlist): nearest to the mean of its tracks, excluding the set itself."""
    M = lib.M.get(space, lib.X)
    rows = [lib.idx[i] for i in ids if i in lib.idx]
    if not rows:
        return []
    sims = M @ unit(M[rows].mean(0, keepdims=True))[0]
    sims[rows] = -2
    return [int(j) for j in np.argsort(-sims)[:k]]


@app.post("/api/tags")
def set_tags(ids: list[str] = Body(...), tag: str = Body(...), status: str = Body(...)):
    """status: confirmed | rejected | clear. Returns the tag's recomputed scores."""
    con = lib.con
    row = con.execute("SELECT id FROM tags WHERE name=?", (tag,)).fetchone()
    if not row:
        raise HTTPException(404, "unknown tag")
    with lib.lock:
        for i in ids:
            if status == "clear":
                con.execute("DELETE FROM track_tags WHERE track_id=? AND tag_id=?", (i, row[0]))
            else:
                con.execute("INSERT OR REPLACE INTO track_tags VALUES (?,?,?,?,?)", (i, row[0], status, "user", db.now()))
        con.commit()
        lib.scores[tag] = lib.score(tag)
        pos, _ = lib.marks(tag)
    return {"tag": tag, "trained": len(pos) >= MIN_TRAIN, "scores": np.round(lib.scores[tag], 2).tolist()}


@app.get("/api/tags/info")
def tags_info():
    """Settings › Tags: keys present, lookup coverage, confirmed tags per axis by source, and the lookup job."""
    from . import known

    return known.info(lib.con) | {"job": tag_job.state()}


@app.post("/api/tags/keys")
def tags_keys(discogs: str | None = Body(None), lastfm: str | None = Body(None)):
    """Save API keys to data/keys.json ("" removes one) and test the ones given. Keys are never sent back."""
    from . import known

    return known.save_keys({"discogs": discogs, "lastfm": lastfm})


@app.post("/api/tags/run")
def tags_run():
    tag_job.start()
    return tag_job.state()


@app.delete("/api/tags/run")
def tags_stop():
    tag_job.stop()
    return tag_job.state()


@app.post("/api/tags/resolve")
def tags_resolve():
    """Re-run tagging from what's already looked up (seconds), e.g. after the taxonomy maps change."""
    from . import known

    stats = known.resolve(log=lambda *_: None)
    lib.reload()
    return stats


@app.get("/api/tags/why")
def tags_why(i: int):
    """Why a track has the tags and suggestions it has (known.explain), plus its top scores per axis."""
    from . import known
    from .rbsync import label

    if not 0 <= i < len(lib.ids):
        raise HTTPException(404)
    out = known.explain(lib.con, lib.ids[i])
    out["scores"] = {c: sorted(([g, round(float(lib.scores[g][i]), 2)] for g in gs if g in lib.scores), key=lambda x: -x[1])[:4]
                     for c, gs in lib.category_tags().items()}
    p, _ = _mytag_plan()  # what a rekordbox sync sends for this track
    out["rekordbox"] = {c: [[label(g), how] for g, how in got] for c, got in p.get(lib.ids[i], {}).items()}
    return out


@app.post("/api/likes")
def like(id: str = Body(...), liked: bool = Body(...)):
    if liked:
        lib.con.execute("INSERT OR IGNORE INTO likes VALUES (?,?)", (id, db.now()))
    else:
        lib.con.execute("DELETE FROM likes WHERE track_id=?", (id,))
    lib.con.commit()
    return {"id": id, "liked": liked}


@app.post("/api/vibes")
def new_vibe(name: str = Body(...), ids: list[str] = Body(...)):
    """Name a set of tracks: becomes a tag (category Vibe) trained on them."""
    name = name.strip().lower()
    lib.con.execute("INSERT OR IGNORE INTO tags(category, name, prompt) VALUES ('Vibe', ?, NULL)", (name,))
    lib.con.commit()
    return set_tags(ids=ids, tag=name, status="confirmed") | {"category": "Vibe"}


@app.post("/api/clusters")
def rename_cluster(space: str = Body(...), cluster: int = Body(...), label: str = Body(...), level: str = Body("cluster")):
    """level: cluster (a sub-cluster) | family. Renamed groups keep their name across re-layouts."""
    table, col = ("families", "family") if level == "family" else ("clusters", "cluster")
    lib.con.execute(f"UPDATE {table} SET label=?, label_source='user' WHERE space=? AND {col}=?", (label.strip(), space, cluster))
    lib.con.commit()
    return {"ok": True}


@app.get("/api/playlists")
def playlists():
    return lib.playlists()


def _write_tracks(pid: int, ids: list[str]) -> None:
    lib.con.execute("DELETE FROM playlist_tracks WHERE playlist_id=?", (pid,))
    lib.con.executemany("INSERT INTO playlist_tracks VALUES (?,?,?)", [(pid, k, i) for k, i in enumerate(ids)])


def _playlist(pid: int) -> dict:
    p = next((p for p in lib.playlists() if p["id"] == pid), None)
    if not p:
        raise HTTPException(404, "no such playlist")
    return p


@app.post("/api/playlists")
def create_playlist(name: str = Body(...), ids: list[str] = Body(...), kind: str = Body("playlist"), data: dict | None = Body(None),
                    folder: int = Body(0)):
    with lib.lock:
        cur = lib.con.execute("INSERT INTO playlists(name, kind, data, created_at, updated_at, version, folder_id) VALUES (?,?,?,?,?,1,?)",
                              (name, kind, json.dumps(data), db.now(), db.now(), _folder_id(folder)))
        _write_tracks(cur.lastrowid, ids)
        lib.con.commit()
    return {"id": cur.lastrowid, "version": 1}


@app.put("/api/playlists/{pid}")
def update_playlist(pid: int, name: str = Body(None), ids: list[str] = Body(None), data: dict | None = Body(None),
                    folder: int | None = Body(None), base: int | None = Body(None)):
    """base: the version the client edited. If the playlist changed since (another device), nothing is written and the
    reply is 409 with the current playlist, so the client can re-apply its edit to it. folder: move it there (0: top level)."""
    with lib.lock:
        cur = _playlist(pid)
        if base is not None and base != cur["version"]:
            return JSONResponse({"conflict": True, "playlist": cur}, status_code=409)
        if name is not None:
            lib.con.execute("UPDATE playlists SET name=? WHERE id=?", (name, pid))
        if folder is not None:
            lib.con.execute("UPDATE playlists SET folder_id=? WHERE id=?", (_folder_id(folder), pid))
        if data is not None:
            lib.con.execute("UPDATE playlists SET data=? WHERE id=?", (json.dumps(data), pid))
        if ids is not None:
            _write_tracks(pid, ids)
        lib.con.execute("UPDATE playlists SET updated_at=?, version=COALESCE(version, 0) + 1 WHERE id=?", (db.now(), pid))
        lib.con.commit()
        return _playlist(pid)


@app.delete("/api/playlists/{pid}")
def delete_playlist(pid: int):
    lib.con.execute("DELETE FROM playlist_tracks WHERE playlist_id=?", (pid,))
    lib.con.execute("DELETE FROM playlists WHERE id=?", (pid,))
    lib.con.commit()
    return {"ok": True}


def _folder_id(fid: int) -> int | None:
    """A folder id from a client: 0 means the top level."""
    if not fid:
        return None
    if not lib.con.execute("SELECT 1 FROM folders WHERE id=?", (fid,)).fetchone():
        raise HTTPException(404, "no such folder")
    return fid


@app.get("/api/folders")
def folders():
    return lib.folders()


@app.post("/api/folders")
def create_folder(name: str = Body(...), parent: int = Body(0)):
    with lib.lock:
        cur = lib.con.execute("INSERT INTO folders(name, parent_id, created_at, updated_at) VALUES (?,?,?,?)",
                              (name.strip(), _folder_id(parent), db.now(), db.now()))
        lib.con.commit()
    return {"id": cur.lastrowid, "name": name.strip(), "parent": _folder_id(parent)}


@app.put("/api/folders/{fid}")
def update_folder(fid: int, name: str = Body(None), parent: int | None = Body(None)):
    """Rename, or move under another folder (0: top level). A folder can't move into itself or its own subfolders."""
    with lib.lock:
        _folder_id(fid)
        if name is not None:
            lib.con.execute("UPDATE folders SET name=? WHERE id=?", (name.strip(), fid))
        if parent is not None:
            up = _folder_id(parent)
            while up is not None:
                if up == fid:
                    raise HTTPException(400, "a folder can't go inside itself")
                up = lib.con.execute("SELECT parent_id FROM folders WHERE id=?", (up,)).fetchone()[0]
            lib.con.execute("UPDATE folders SET parent_id=? WHERE id=?", (_folder_id(parent), fid))
        lib.con.execute("UPDATE folders SET updated_at=? WHERE id=?", (db.now(), fid))
        lib.con.commit()
    return next(f for f in lib.folders() if f["id"] == fid)


@app.delete("/api/folders/{fid}")
def delete_folder(fid: int):
    """Removes only the folder: its playlists and subfolders move up to its parent."""
    with lib.lock:
        _folder_id(fid)
        parent = lib.con.execute("SELECT parent_id FROM folders WHERE id=?", (fid,)).fetchone()[0]
        lib.con.execute("UPDATE folders SET parent_id=? WHERE parent_id=?", (parent, fid))
        lib.con.execute("UPDATE playlists SET folder_id=?, updated_at=?, version=COALESCE(version, 0) + 1 WHERE folder_id=?",
                        (parent, db.now(), fid))
        lib.con.execute("DELETE FROM folders WHERE id=?", (fid,))
        lib.con.commit()
    return {"ok": True}


@app.get("/api/rekordbox")
def rekordbox_status():
    return rekordbox.status(lib.con)


def _trained_tags() -> set[str]:
    return {r[0] for r in lib.con.execute(
        "SELECT g.name FROM track_tags tt JOIN tags g ON g.id=tt.tag_id WHERE tt.status='confirmed' GROUP BY g.name HAVING COUNT(*)>=?",
        (MIN_TRAIN,))}


def _mytag_plan() -> tuple[dict, dict]:
    from . import rbsync

    return rbsync.plan(lib, _trained_tags()), {i: lib.rows[i]["path"] for i in lib.ids if i in lib.rows}


@app.get("/api/rekordbox/running")
def rekordbox_running():
    from . import rbguard

    return {"running": rbguard.running(), "lock": rbguard.status()}


@app.post("/api/rekordbox/lock")
def rekordbox_lock(locked: bool = Body(...), once: bool = Body(False)):
    """The rekordbox lock (rbguard): while locked, nothing in Summit can change rekordbox. Unlocking is an app action."""
    from . import rbguard

    return rbguard.set_locked(locked, once)


@app.get("/api/rekordbox/tags")
def rekordbox_tags():
    """Tools › rekordbox My Tags: what a sync would send, what rekordbox has now, backups, and the last sync."""
    from . import rbguard, rbsync

    with lib.lock:
        p, paths = _mytag_plan()
    try:
        cur, err = rbsync.current(), None
    except Exception as e:  # rekordbox missing, or its library can't be opened
        cur, err = None, str(e)
    return {"running": rbguard.running(), "lock": rbguard.status(), "plan": rbsync.summary(p, paths, cur["paths"] if cur else None),
            "current": {k: v for k, v in cur.items() if k != "paths"} | {"tracks": len(cur["paths"])} if cur else None,
            "error": err, "backups": rbguard.backups(), "last": rekordbox.get(lib.con, "mytag_sync"),
            "likely": rbsync.LIKELY, "min_train": MIN_TRAIN}


@app.post("/api/rekordbox/tags/sync")
def rekordbox_tags_sync():
    from . import rbsync

    with lib.lock:
        p, paths = _mytag_plan()
        try:
            res = rbsync.write(p, paths)
        except RuntimeError as e:  # rekordbox is open (423: 409 means a playlist conflict to the client)
            raise HTTPException(423, str(e))
        if not res.get("noop"):
            rekordbox.put(lib.con, "mytag_sync", {"at": time.time(), **res})
            lib.con.commit()
    return res


@app.post("/api/rekordbox/tags/restore")
def rekordbox_tags_restore(stamp: str = Body(..., embed=True)):
    from . import rbguard

    try:
        return rbguard.restore(stamp)
    except FileNotFoundError as e:
        raise HTTPException(404, str(e))
    except RuntimeError as e:
        raise HTTPException(423, str(e))


@app.post("/api/rekordbox/import")
def rekordbox_import(path: str | None = Body(None, embed=True)):
    """Read rekordbox's collection export (File → Export Collection in xml format)."""
    with lib.lock:
        try:
            res = rekordbox.import_collection(lib.con, path)
        except FileNotFoundError as e:
            raise HTTPException(404, str(e))
        lib.load_rekordbox()
    return res


@app.post("/api/export")
def export():
    with lib.lock:
        res = export_all(lib.con)
        lib.load_rekordbox()  # the export may have re-imported a newer rekordbox export first
    return res


@app.get("/api/pending")
def library_pending():
    """Library files not on the map yet (see scan.pending), and the state of any update run."""
    return {**({} if job.running else pending(lib.con)), "job": job.state()}


UI_REV = {"rev": f"{time.time():.0f}:0"}
UI_SEEN = {}  # window id → (revision it has loaded, when it last asked)


@app.get("/api/ui/rev")
def ui_rev(have: str = "", win: str = ""):
    if win:
        UI_SEEN[win] = (have, time.time())
    return UI_REV


@app.get("/api/ui/windows")
def ui_windows():
    """Windows polled in the last 10 s and the revision each has loaded (summit reload waits on this)."""
    now = time.time()
    return {"rev": UI_REV["rev"], "windows": {w: h for w, (h, t) in UI_SEEN.items() if now - t < 10}}


@app.post("/api/ui/reload")
def ui_reload():
    start, n = UI_REV["rev"].split(":")
    UI_REV["rev"] = f"{start}:{int(n) + 1}"
    return UI_REV


# ---- Phone streaming (media.py, streaming.py): copies made ahead, keep-awake, Tailscale path ----
@app.get("/api/stream/info")
def stream_info():
    from . import streaming
    from .media import coverage

    with lib.lock:
        off = offline_set()
    return {"coverage": coverage(lib.con), "job": stream_job.state(), "awake": streaming.awake_state(),
            "offline": {"tracks": len(off["tracks"]), "bytes": off["bytes"], "budget_mb": off["budget_mb"], "oldest_added": off["oldest_added"]},
            "tailscale": streaming.tailscale()}


OFFLINE_MB = 6144  # the phone keeps the most recently added tracks, up to this, in data-saver quality


def offline_set(budget_mb: int = OFFLINE_MB) -> dict:
    """The phone's offline set: the most recently added tracks whose 128k copies fit in the budget, newest first.
    "Added" is rekordbox's DateAdded where the collection export has the track (Summit's own first scan stamped most
    of the library with one day), else when Summit first saw the file."""
    from .media import cached_path, needs_transcode

    rb = rekordbox.dates_added(lib.con)
    added = {t: rb.get(rekordbox.norm(lib.rows[t]["path"]), lib.rows[t]["added_at"] or 0) for t in lib.ids}
    budget, used, out = budget_mb * 1024 * 1024, 0, []
    for i in sorted(lib.ids, key=lambda t: -added[t]):
        r = lib.rows[i]
        p = cached_path(r, "low") if needs_transcode(r, "low") else Path(r["path"])
        size = p.stat().st_size if p.exists() else 0
        if not size:
            continue  # not converted yet: next time
        if used + size > budget:
            break
        used += size
        out.append({"id": i, "bytes": size})
    oldest = added[out[-1]["id"]] if out else None
    return {"budget_mb": budget_mb, "bytes": used, "tracks": out, "oldest_added": oldest}


@app.get("/api/offline")
def offline(budget_mb: int = OFFLINE_MB):
    """What the phone should keep offline (playback.OfflineSync): newest tracks first, 128k, within budget_mb."""
    with lib.lock:
        return offline_set(max(256, min(budget_mb, 65536)))


@app.post("/api/stream/run")
def stream_run():
    stream_job.start()
    return stream_job.state()


@app.delete("/api/stream/run")
def stream_stop():
    stream_job.stop()
    return stream_job.state()


@app.post("/api/stream/awake")
def stream_awake(on: bool = Body(..., embed=True)):
    from . import streaming

    return streaming.set_keep_awake(on)


@app.post("/api/reload")
def reload_library():
    """Called by terminal runs of scan / analyze / layout / label / update (pipeline.notify_server)."""
    lib.reload()
    return {"ok": True}


@app.get("/api/update")
def update_state():
    return job.state()


@app.post("/api/update")
def update_start():
    job.start()
    return job.state()


@app.delete("/api/update")
def update_stop():
    job.stop()
    return job.state()


app.mount("/web", StaticFiles(directory=WEB), name="web")
