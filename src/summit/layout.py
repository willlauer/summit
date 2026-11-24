"""Step 3: 2D maps + a two-level, data-driven organisation per embedding space.

- Families: HDBSCAN on a 10D UMAP of the whole space. Boundaries only where the embedding has density gaps.
- Sub-clusters: each family is re-projected on its own (UMAP spreads out its internal structure) and re-clustered.
  A family is split only if HDBSCAN finds at least two stable sub-clusters covering most of it; otherwise it stays
  whole. There is no target count anywhere.
Noise points join the nearest cluster so every track has a place. User-edited names survive re-runs (matched by
membership overlap).
"""

import numpy as np

from . import db
from .config import MAX_DURATION, SEED, SPACES, UMAP_NEIGHBORS

MIN_SHARE = 1 / 80  # smallest sub-cluster worth showing, as a share of the library (~48 of 3,860 tracks)
RESPLIT_SHARE = 0.15  # a sub-cluster bigger than this share of the library is examined again, one level deeper


def load(con, kind: str, ids: list[str]) -> np.ndarray:
    vecs = {r["track_id"]: db.from_blob(r["vec"]) for r in con.execute("SELECT track_id, vec FROM vectors WHERE kind=?", (kind,))}
    return np.stack([vecs[i] for i in ids])


def unit(X: np.ndarray) -> np.ndarray:
    return X / np.linalg.norm(X, axis=1, keepdims=True).clip(1e-9)


def analyzed_ids(con) -> list[str]:
    return [r[0] for r in con.execute(
        "SELECT t.id FROM tracks t JOIN vectors v ON v.track_id=t.id AND v.kind='clap' WHERE t.missing=0 AND t.duration <= ? ORDER BY t.id",
        (MAX_DURATION,))]


def space_matrix(con, space: str, ids: list[str]) -> np.ndarray:
    kinds = SPACES[space]["kinds"]
    return np.hstack([unit(load(con, k, ids)) for k in kinds]) / np.sqrt(len(kinds))


def _umap(X: np.ndarray, dims: int, min_dist: float) -> np.ndarray:
    import umap

    return umap.UMAP(n_components=dims, n_neighbors=min(UMAP_NEIGHBORS, len(X) - 1), min_dist=min_dist,
                     metric="cosine", random_state=SEED).fit_transform(X)


def _attach_noise(z: np.ndarray, lab: np.ndarray) -> np.ndarray:
    """Noise (-1) joins the nearest cluster centroid; clusters renumbered largest-first."""
    lab = lab.copy()
    k = lab.max() + 1
    cent = np.stack([z[lab == c].mean(0) for c in range(k)])
    noise = lab < 0
    if noise.any():
        lab[noise] = ((z[noise, None, :] - cent[None]) ** 2).sum(-1).argmin(1)
    order = np.argsort(-np.bincount(lab, minlength=k))
    remap = np.empty_like(order)
    remap[order] = np.arange(k)
    return remap[lab]


def families(z: np.ndarray) -> tuple[np.ndarray, float]:
    from sklearn.cluster import HDBSCAN

    n = len(z)
    lab = HDBSCAN(min_cluster_size=max(25, n // 100), min_samples=10).fit_predict(z)
    if lab.max() < 0:
        return np.zeros(n, dtype=int), 1.0
    return _attach_noise(z, lab), float(np.mean(lab >= 0))


def split(X: np.ndarray, min_size: int) -> np.ndarray | None:
    """Sub-clusters of one group, or None if it has no stable internal structure."""
    from sklearn.cluster import HDBSCAN

    n = len(X)
    if n < 2 * min_size:
        return None
    z = _umap(X, 10, 0.0)
    for method in ("eom", "leaf"):
        lab = HDBSCAN(min_cluster_size=min_size, min_samples=8, cluster_selection_method=method).fit_predict(z)
        k, inside = lab.max() + 1, lab >= 0
        # Accept only real structure: 2+ sub-clusters, most tracks inside one, no single sub-cluster swallowing it all.
        if 2 <= k <= 12 and inside.mean() >= 0.5 and np.bincount(lab[inside]).max() / inside.sum() < 0.85:
            return _attach_noise(z, lab)
    return None


def _carry(old: dict, members: set) -> str | None:
    """User-named group whose membership mostly matches (Jaccard > 0.5)."""
    best, score = None, 0.0
    for o in old.values():
        j = len(o["ids"] & members) / len(o["ids"] | members)
        if j > score:
            best, score = o, j
    return best["label"] if best and score > 0.5 else None


def layout(log=print) -> None:
    con = db.connect()
    ids = analyzed_ids(con)
    if len(ids) < 20:
        log("layout: need at least 20 analyzed tracks")
        return
    log(f"layout: {len(ids)} tracks")
    for space in SPACES:
        X = space_matrix(con, space, ids)
        xy = _umap(X, 2, 0.1)
        xy = (xy - xy.mean(0)) / np.abs(xy - xy.mean(0)).max() * 10  # fit roughly in [-10, 10]
        fam, covered = families(_umap(X, 10, 0.0))
        min_size, big = max(20, round(len(ids) * MIN_SHARE)), len(ids) * RESPLIT_SHARE

        def leaves(m: np.ndarray, depth: int = 0) -> list[np.ndarray]:
            """Split a group where the data supports it; re-examine any part that is still very large."""
            sub = split(X[m], min_size) if depth < 2 else None
            if sub is None:
                return [m]
            out = []
            for c in range(sub.max() + 1):
                part = m[sub == c]
                out += leaves(part, depth + 1) if len(part) > big else [part]
            return out

        leaf = np.zeros(len(ids), dtype=int)
        nleaf, parts = 0, []
        for f in range(fam.max() + 1):
            groups = sorted(leaves(np.flatnonzero(fam == f)), key=len, reverse=True)
            for g in groups:
                leaf[g] = nleaf
                nleaf += 1
            parts.append(f"{sum(map(len, groups))}" + (f"→[{' '.join(str(len(g)) for g in groups)}]" if len(groups) > 1 else ""))
        log(f"  {space}: {fam.max() + 1} families ({covered:.0%} in a dense region), {nleaf} sub-clusters")
        log(f"    {' · '.join(parts)}")

        old_f, old_c = {}, {}
        for r in con.execute("SELECT l.track_id, l.family, l.cluster, f.label fl, f.label_source fs, c.label cl, c.label_source cs FROM layouts l "
                             "LEFT JOIN families f ON f.space=l.space AND f.family=l.family LEFT JOIN clusters c ON c.space=l.space AND c.cluster=l.cluster "
                             "WHERE l.space=?", (space,)):
            if r["fs"] == "user":
                old_f.setdefault(r["family"], {"label": r["fl"], "ids": set()})["ids"].add(r["track_id"])
            if r["cs"] == "user":
                old_c.setdefault(r["cluster"], {"label": r["cl"], "ids": set()})["ids"].add(r["track_id"])
        for t in ("layouts", "families", "clusters"):
            con.execute(f"DELETE FROM {t} WHERE space=?", (space,))
        con.executemany("INSERT INTO layouts(space, track_id, x, y, cluster, family) VALUES (?,?,?,?,?,?)",
                        [(space, i, float(x), float(y), int(c), int(f)) for i, (x, y), c, f in zip(ids, xy, leaf, fam)])
        for f in range(fam.max() + 1):
            kept = _carry(old_f, {ids[k] for k in np.flatnonzero(fam == f)})
            con.execute("INSERT INTO families VALUES (?,?,?,?,?,?)", (space, f, kept, "user" if kept else "auto", "[]", "[]"))
        for c in range(nleaf):
            m = np.flatnonzero(leaf == c)
            kept = _carry(old_c, {ids[k] for k in m})
            con.execute("INSERT INTO clusters(space, cluster, family, label, label_source, styles, vibes) VALUES (?,?,?,?,?,?,?)",
                        (space, c, int(fam[m[0]]), kept, "user" if kept else "auto", "[]", "[]"))
        con.commit()
    layout3d(con, ids, log)
    from .label import label

    label(log=log)


def layout3d(con=None, ids=None, log=print) -> None:
    """3D projection of each space (the map's 3D view). Positions only: families and sub-clusters come from the 2D run."""
    con = con or db.connect()
    ids = ids or analyzed_ids(con)
    for space in SPACES:
        xyz = _umap(space_matrix(con, space, ids), 3, 0.1)
        xyz = (xyz - xyz.mean(0)) / np.abs(xyz - xyz.mean(0)).max() * 10
        con.execute("DELETE FROM layouts3d WHERE space=?", (space,))
        con.executemany("INSERT INTO layouts3d VALUES (?,?,?,?,?)", [(space, i, *map(float, p)) for i, p in zip(ids, xyz)])
        con.commit()
        log(f"  {space}: 3D layout")
