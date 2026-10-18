"""Step 4: name families and sub-clusters from their most *distinctive* Discogs styles.

Families are named by lift over the library average; sub-clusters by lift over their own family, so the house
family's sub-clusters read "Tech House" / "Progressive House" rather than "House" every time. A family that wasn't
split has a single sub-cluster that shares its name. User-edited names are never overwritten.
"""

import json

import numpy as np

from . import db
from .analyze import style_names
from .config import SPACES


def short(style: str) -> str:
    return style.split("---")[-1]


def describe(S: np.ndarray, m: np.ndarray, base: np.ndarray, names: list[str], taken: set = frozenset()) -> tuple[str, list]:
    """Name from the styles most over-represented vs `base`. Short names repeat across Discogs genres
    ("Electronic---Disco", "Funk / Soul---Disco"), so dedupe on the short name; avoid names already `taken`."""
    mean = S[m].mean(0)
    lift = mean - base
    top, seen = [], set()
    for i in np.argsort(-lift):
        s = short(names[i])
        if s not in seen:
            seen.add(s)
            top.append(i)
        if len(top) == 6:
            break
    styles = [[short(names[i]), round(float(mean[i]), 3), round(float(lift[i]), 3)] for i in top]
    words = [short(names[i]) for i in top]
    for a, b in [(0, 1), (0, 2), (1, 2), (0, 3), (1, 3), (2, 3)]:
        name = f"{words[a]} / {words[b]}"
        if name not in taken:
            return name, styles
    return " / ".join(words[:3]), styles


def label(log=print) -> None:
    from .layout import analyzed_ids, load, unit
    from .tags import zero_shot

    con = db.connect()
    ids = analyzed_ids(con)
    if not ids:
        return
    names = style_names()
    S = load(con, "styles", ids)
    lib_mean = S.mean(0)
    Z = zero_shot(con, unit(load(con, "clap", ids)))  # {tag: scores}
    idx = {i: k for k, i in enumerate(ids)}
    vibes = lambda m: sorted(Z, key=lambda t: -float(np.mean(Z[t][m])))[:3]
    for space in SPACES:
        fam, leaf = {}, {}
        for r in con.execute("SELECT track_id, family, cluster FROM layouts WHERE space=?", (space,)):
            if r["track_id"] in idx:
                fam.setdefault(r["family"], []).append(idx[r["track_id"]])
                leaf.setdefault(r["cluster"], []).append(idx[r["track_id"]])
        fam_names = {}
        for f, m in fam.items():
            m = np.array(m)
            auto, styles = describe(S, m, lib_mean, names)
            fam_names[f] = auto
            con.execute("UPDATE families SET styles=?, vibes=?, label=CASE WHEN label_source='user' THEN label ELSE ? END WHERE space=? AND family=?",
                        (json.dumps(styles), json.dumps(vibes(m)), auto, space, f))
        parent = {r["cluster"]: r["family"] for r in con.execute("SELECT cluster, family FROM clusters WHERE space=?", (space,))}
        siblings = {}
        for c, f in parent.items():
            siblings.setdefault(f, []).append(c)
        taken = {f: set() for f in fam}
        for c in sorted(leaf, key=lambda c: -len(leaf[c])):  # biggest sub-clusters pick names first
            m, f = np.array(leaf[c]), parent[c]
            if len(siblings[f]) == 1:  # unsplit family: the sub-cluster is the family
                auto, styles = fam_names[f], describe(S, m, lib_mean, names)[1]
            else:  # contrast with its siblings: lift over the rest of the family
                rest = np.setdiff1d(np.array(fam[f]), m)
                auto, styles = describe(S, m, S[rest].mean(0), names, taken[f])
                taken[f].add(auto)
            con.execute("UPDATE clusters SET styles=?, vibes=?, label=CASE WHEN label_source='user' THEN label ELSE ? END WHERE space=? AND cluster=?",
                        (json.dumps(styles), json.dumps(vibes(m)), auto, space, c))
        con.commit()
        log(f"  {space}:")
        for f in sorted(fam):
            fl = con.execute("SELECT label FROM families WHERE space=? AND family=?", (space, f)).fetchone()[0]
            subs = con.execute("SELECT cluster, label FROM clusters WHERE space=? AND family=? ORDER BY cluster", (space, f)).fetchall()
            inner = "" if len(subs) == 1 else " › " + " | ".join(f"{r['label']} ({len(leaf[r['cluster']])})" for r in subs)
            log(f"    {fl} ({len(fam[f])}){inner}")
