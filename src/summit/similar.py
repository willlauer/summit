"""How tracks are compared. Raw CLAP / EffNet cosines are dominated by what every track in the library shares
("this is electronic dance music"), so everything looks fairly similar to everything and neighbours drift toward the
broad genre. Three corrections, all cheap and unsupervised:

1. Centre + partial whitening per vector kind: remove the shared mean, then rotate onto principal axes and shrink the
   few dominant axes so finer differences (groove, timbre, production) get a say.
2. Discogs styles join the combined space (as square roots, so a 0.04 vs 0.01 style counts, not just the top one).
3. Hubness correction (CSLS) when ranking neighbours: some tracks sit near the middle of the space and show up as a
   neighbour of everything; each candidate is penalised by how close it is to its own nearest neighbours.

`summit similar-eval` scores the variants against your playlists (do tracks you put together come up as neighbours?).
"""

import numpy as np

WHITEN_DIMS = 256  # principal axes kept per kind
WHITEN_POWER = 0.5  # 0 = just centre, 1 = full whitening (amplifies noise); 0.5 is the usual safe middle
HUB_K = 10  # neighbours used to measure how "central" a track is


def unit(X: np.ndarray) -> np.ndarray:
    return X / np.linalg.norm(X, axis=1, keepdims=True).clip(1e-9)


def prep(X: np.ndarray, kind: str, power: float = WHITEN_POWER) -> np.ndarray:
    """One vector kind, ready to be combined with others: centred, partially whitened, unit length."""
    X = np.asarray(X, dtype=np.float64)
    if kind == "styles":
        X = np.sqrt(np.clip(X, 0, None))  # Hellinger: compare probability profiles, not just the argmax
    else:
        X = unit(X)
    X = X - X.mean(0)
    if power > 0 and len(X) > 2:
        _, S, Vt = np.linalg.svd(X, full_matrices=False)
        keep = int(min(WHITEN_DIMS, np.sum(S > S[0] * 1e-6)))
        X = X @ Vt[:keep].T / S[:keep] ** power
    return unit(X).astype(np.float32)


def hubness(M: np.ndarray, k: int = HUB_K, chunk: int = 1024) -> np.ndarray:
    """Mean cosine of each track to its k nearest neighbours (high = sits in a crowded middle)."""
    n = len(M)
    k = max(1, min(k, n - 1))
    r = np.zeros(n, dtype=np.float32)
    for s in range(0, n, chunk):
        S = M[s : s + chunk] @ M.T
        S[np.arange(len(S)), np.arange(s, s + len(S))] = -np.inf
        r[s : s + chunk] = np.partition(S, -k, axis=1)[:, -k:].mean(1)
    return r


def ranked(M: np.ndarray, R: np.ndarray | None, q: np.ndarray) -> np.ndarray:
    """Similarity of every track to query vector q, hubness-corrected when R is given (CSLS, up to a constant)."""
    sims = M @ q
    return sims - 0.5 * R if R is not None else sims


def evaluate(log=print) -> None:
    """Precision@10 of 'similar tracks' against playlist co-membership, for each way of comparing tracks."""
    from . import db
    from .config import SPACES
    from .layout import analyzed_ids, load

    con = db.connect()
    ids = analyzed_ids(con)
    idx = {t: k for k, t in enumerate(ids)}
    lists = {}
    for pid, tid in con.execute("SELECT pt.playlist_id, pt.track_id FROM playlist_tracks pt JOIN playlists p ON p.id=pt.playlist_id "
                                "WHERE coalesce(p.kind,'playlist')='playlist'"):
        if tid in idx:
            lists.setdefault(pid, set()).add(idx[tid])
    # Playlists that say "these belong together": not tiny, not a big catch-all.
    lists = [m for m in lists.values() if 5 <= len(m) <= max(50, len(ids) // 10)]
    if not lists:
        log("similar-eval: needs playlists with 5+ analyzed tracks")
        return
    together: dict[int, set] = {}
    for m in lists:
        for t in m:
            together.setdefault(t, set()).update(m - {t})
    queries = np.array(sorted(together))
    raw = {k: load(con, k, ids) for k in ("clap", "effnet", "styles")}
    log(f"similar-eval: {len(ids)} tracks · {len(lists)} playlists · {len(queries)} query tracks · precision@10 (higher is better)")

    def score(M, R):
        hits = 0
        for s in range(0, len(queries), 512):
            q = queries[s : s + 512]
            S = M[q] @ M.T - (0.5 * R if R is not None else 0)
            S[np.arange(len(q)), q] = -np.inf
            top = np.argpartition(-S, 10, axis=1)[:, :10]
            hits += sum(len(together[t] & set(row.tolist())) for t, row in zip(q, top))
        return hits / (10 * len(queries))

    variants = [("before (raw cosine)", ["clap", "effnet"], 0.0, False, False)]
    for kinds in (["clap", "effnet"], ["clap", "effnet", "styles"], ["clap"], ["effnet"]):
        for power in (0.0, 0.5, 1.0):
            variants.append((f"{'+'.join(kinds)} · whiten {power}", kinds, power, True, False))
    rows = []
    for name, kinds, power, centred, _ in variants:
        if centred:
            M = np.hstack([prep(raw[k], k, power) for k in kinds]) / np.sqrt(len(kinds))
        else:
            M = (np.hstack([unit(raw[k]) for k in kinds]) / np.sqrt(len(kinds))).astype(np.float32)
        rows.append((name, score(M, None), score(M, hubness(M))))
    w = max(len(r[0]) for r in rows)
    log(f"  {'':{w}}  plain   +hub fix")
    for name, a, b in rows:
        log(f"  {name:{w}}  {a:.3f}   {b:.3f}")
    cur = SPACES.get("combined", {}).get("kinds")
    log(f"  (the app's combined space now uses {'+'.join(cur)} · whiten {WHITEN_POWER} · hub fix)")
