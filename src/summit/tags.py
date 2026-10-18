"""Tag scores. Each tag starts as a zero-shot CLAP prompt; once you confirm enough tracks it becomes a trained
classifier (logistic regression on CLAP + EffNet), so tags reflect *your* definitions."""

import numpy as np

MIN_TRAIN = 5  # confirmed examples before a tag switches to a trained classifier


def zero_shot(con, C: np.ndarray) -> dict[str, np.ndarray]:
    """Prompt similarity, z-scored per tag across the library, squashed to 0..1 (~top 10% land above 0.6)."""
    from .clap_text import embed

    rows = con.execute("SELECT name, prompt FROM tags WHERE prompt IS NOT NULL AND prompt != '' ORDER BY id").fetchall()
    if not rows:
        return {}
    T = embed([r["prompt"] for r in rows])
    sims = C @ T.T
    z = (sims - sims.mean(0)) / sims.std(0).clip(1e-6)
    return {r["name"]: 1 / (1 + np.exp(-1.6 * (z[:, k] - 1.0))) for k, r in enumerate(rows)}


def trained(X: np.ndarray, pos: np.ndarray, neg: np.ndarray, seed: int = 0) -> np.ndarray:
    """Classifier score for every track. Negatives: explicit rejections plus a random background sample."""
    from sklearn.linear_model import LogisticRegression

    rng = np.random.default_rng(seed)
    others = np.setdiff1d(np.arange(len(X)), np.concatenate([pos, neg]))
    bg = rng.choice(others, size=min(len(others), max(50, 4 * len(pos))), replace=False)
    Xi = np.concatenate([X[pos], X[neg], X[bg]])
    y = np.concatenate([np.ones(len(pos)), np.zeros(len(neg) + len(bg))])
    w = np.concatenate([np.ones(len(pos)), np.full(len(neg), 2.0), np.full(len(bg), 0.5)])
    clf = LogisticRegression(C=1.0, max_iter=500, class_weight="balanced").fit(Xi, y, sample_weight=w)
    return clf.predict_proba(X)[:, 1]
