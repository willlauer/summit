"""Step 5 backend: FastAPI over the SQLite store. Serves the library, audio (Range-capable), search, tags,
clusters, playlists, vibes and export. The web UI lives in web/ and is served from the same origin."""

import json
import threading

import numpy as np
from fastapi import Body, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from . import clap_text, db
from .config import DEFAULT_SPACE, MAX_DURATION, SPACES, TAG_SPACE, WEB
from .export import export_all
from .layout import load, space_matrix, unit
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

    def tag_names(self) -> list[str]:
        return [r[0] for r in self.con.execute("SELECT name FROM tags ORDER BY id")]

    def marks(self, tag: str) -> tuple[np.ndarray, np.ndarray]:
        rows = self.con.execute(
            "SELECT tt.track_id, tt.status FROM track_tags tt JOIN tags g ON g.id=tt.tag_id WHERE g.name=?", (tag,)).fetchall()
        pos = [self.idx[r[0]] for r in rows if r[1] == "confirmed" and r[0] in self.idx]
        neg = [self.idx[r[0]] for r in rows if r[1] == "rejected" and r[0] in self.idx]
        return np.array(pos, dtype=int), np.array(neg, dtype=int)

    def score(self, tag: str) -> np.ndarray:
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

    def payload(self) -> dict:
        with self.lock:
            con = self.con
            tags = {}
            for r in con.execute("SELECT g.name, tt.track_id, tt.status FROM track_tags tt JOIN tags g ON g.id=tt.tag_id"):
                tags.setdefault(r["track_id"], {"confirmed": [], "rejected": []})[r["status"]].append(r["name"])
            liked = [r[0] for r in con.execute("SELECT track_id FROM likes ORDER BY created_at DESC")]
            liked_set = set(liked)
            tracks = []
            for k, i in enumerate(self.ids):
                r = self.rows[i]
                tracks.append({
                    "id": i, "artist": r["artist"], "title": r["title"], "album": r["album"], "dur": round(r["duration"] or 0),
                    "ext": r["format"], "bpm": round(r["bpm"]) if r["bpm"] else None, "key": r["key"], "audition": r["audition"] or 0,
                    "styles": self.styles[k], "liked": i in liked_set, **tags.get(i, {"confirmed": [], "rejected": []}),
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
                    clusters[s] = [group(r, "cluster") | {"family": r["family"]}
                                   for r in con.execute("SELECT * FROM clusters WHERE space=? ORDER BY cluster", (s,))]
                    families[s] = [group(r, "family") for r in con.execute("SELECT * FROM families WHERE space=? ORDER BY family", (s,))]
            return {
                "tracks": tracks, "spaces": spaces, "clusters": clusters, "families": families,
                "spaceList": [{"key": k, "label": v["label"]} for k, v in SPACES.items() if k in spaces], "defaultSpace": DEFAULT_SPACE,
                "tags": [dict(r) for r in con.execute("SELECT name, category FROM tags ORDER BY id")],
                "scores": {t: np.round(v, 2).tolist() for t, v in self.scores.items()},
                "playlists": self.playlists(), "liked": [i for i in liked if i in self.idx],
            }

    def playlists(self) -> list[dict]:
        out = []
        for p in self.con.execute("SELECT * FROM playlists ORDER BY created_at"):
            ids = [r[0] for r in self.con.execute("SELECT track_id FROM playlist_tracks WHERE playlist_id=? ORDER BY position", (p["id"],))]
            out.append({"id": p["id"], "name": p["name"], "kind": p["kind"], "data": json.loads(p["data"] or "null"), "ids": ids})
        return out


app = FastAPI(title="Summit")
lib: Library | None = None


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
    lib = Library()
    clap_text.warm()


@app.get("/")
def index():
    return FileResponse(WEB / "index.html")


@app.get("/api/library")
def library():
    return JSONResponse(lib.payload())


@app.get("/audio/{track_id}")
def audio(track_id: str):
    r = lib.rows.get(track_id)
    if not r:
        raise HTTPException(404)
    types = {"aiff": "audio/aiff", "aif": "audio/aiff", "m4a": "audio/mp4", "mp3": "audio/mpeg", "wav": "audio/wav", "flac": "audio/flac"}
    return FileResponse(r["path"], media_type=types.get(r["format"]))  # Starlette FileResponse handles Range


@app.get("/api/search")
def search(q: str, n: int = 60):
    q = q.strip()
    if not q or not lib.ids:
        return []
    ql = q.lower()
    hits = [k for k, i in enumerate(lib.ids) if ql in f"{lib.rows[i]['artist']} {lib.rows[i]['title']}".lower()]
    if not clap_text.ready():
        return [{"i": k, "score": 1.0} for k in hits[:n]]
    sims = lib.C @ clap_text.embed([q], cache=False)[0]
    sims[hits] += 1.0  # name matches first
    top = np.argsort(-sims)[:n]
    lo, hi = float(sims[top[-1]]), float(sims[top[0]])
    return [{"i": int(k), "score": round((float(sims[k]) - lo) / max(hi - lo, 1e-6), 3)} for k in top]


@app.get("/api/neighbors")
def neighbors(i: int, k: int = 10, space: str = "combined"):
    """Most similar tracks by cosine in the full embedding space (not 2D map distance)."""
    M = lib.M.get(space, lib.X)
    sims = M @ M[i]
    sims[i] = -2
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


@app.post("/api/playlists")
def create_playlist(name: str = Body(...), ids: list[str] = Body(...), kind: str = Body("playlist"), data: dict | None = Body(None)):
    cur = lib.con.execute("INSERT INTO playlists(name, kind, data, created_at, updated_at) VALUES (?,?,?,?,?)",
                          (name, kind, json.dumps(data), db.now(), db.now()))
    _write_tracks(cur.lastrowid, ids)
    lib.con.commit()
    return {"id": cur.lastrowid}


@app.put("/api/playlists/{pid}")
def update_playlist(pid: int, name: str = Body(None), ids: list[str] = Body(None), data: dict | None = Body(None)):
    if name is not None:
        lib.con.execute("UPDATE playlists SET name=?, updated_at=? WHERE id=?", (name, db.now(), pid))
    if data is not None:
        lib.con.execute("UPDATE playlists SET data=?, updated_at=? WHERE id=?", (json.dumps(data), db.now(), pid))
    if ids is not None:
        _write_tracks(pid, ids)
    lib.con.commit()
    return {"ok": True}


@app.delete("/api/playlists/{pid}")
def delete_playlist(pid: int):
    lib.con.execute("DELETE FROM playlist_tracks WHERE playlist_id=?", (pid,))
    lib.con.execute("DELETE FROM playlists WHERE id=?", (pid,))
    lib.con.commit()
    return {"ok": True}


@app.post("/api/export")
def export():
    return export_all(lib.con)


app.mount("/web", StaticFiles(directory=WEB), name="web")
