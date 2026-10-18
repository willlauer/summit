"""CLAP text tower: search queries, tag prompts and cluster vibes all embed text into the audio space."""

import hashlib
import threading

import numpy as np

from .config import CLAP_MODEL, DATA

_lock = threading.Lock()
_model = None
_proc = None
CACHE = DATA / "text_cache.npz"


def _load():
    global _model, _proc
    with _lock:
        if _model is None:
            import torch
            from transformers import ClapModel, ClapProcessor

            _model = ClapModel.from_pretrained(CLAP_MODEL).eval()
            _proc = ClapProcessor.from_pretrained(CLAP_MODEL)
            torch.set_grad_enabled(False)
    return _model, _proc


def ready() -> bool:
    return _model is not None


def warm() -> None:
    threading.Thread(target=_load, daemon=True).start()


def _encode(texts: list[str]) -> np.ndarray:
    import torch

    model, proc = _load()
    with torch.no_grad():
        e = model.get_text_features(**proc(text=texts, return_tensors="pt", padding=True))
    return torch.nn.functional.normalize(e, dim=-1).numpy()


def embed(texts: list[str], cache: bool = True) -> np.ndarray:
    """Unit-normalised text embeddings. Tag prompts are cached on disk so startup doesn't need the model;
    ad-hoc search queries (cache=False) are not."""
    if not cache:
        return _encode(texts)
    cache = dict(np.load(CACHE)) if CACHE.exists() else {}
    keys = [hashlib.sha1(t.encode()).hexdigest()[:16] for t in texts]
    missing = [t for t, k in zip(texts, keys) if k not in cache]
    if missing:
        for t, v in zip(missing, _encode(missing)):
            cache[hashlib.sha1(t.encode()).hexdigest()[:16]] = v
        DATA.mkdir(parents=True, exist_ok=True)
        np.savez(CACHE, **cache)
    return np.stack([cache[k] for k in keys])
