"""Step 2: per-track analysis. CLAP (MPS) + Discogs-EffNet (ONNX) embeddings, Discogs styles, BPM and key.

CPU work (decode, mel spectrograms, EffNet, BPM/key, CLAP feature extraction) runs in a process pool;
the CLAP audio tower runs batched on the GPU in the main process. Resumable: tracks with vectors are skipped.
"""

import json
import os
import subprocess
import time
import urllib.request
import itertools
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, wait

import numpy as np

from . import db
from .config import CLAP_MODEL, EFFNET_META, EFFNET_ONNX, EFFNET_URL, MAX_DURATION, MODELS, N_WINDOWS, SPAN, SR, WINDOW_S

CAMELOT = {  # (key, scale) -> Camelot
    ("A", "minor"): "8A", ("E", "minor"): "9A", ("B", "minor"): "10A", ("F#", "minor"): "11A", ("C#", "minor"): "12A", ("G#", "minor"): "1A",
    ("D#", "minor"): "2A", ("A#", "minor"): "3A", ("F", "minor"): "4A", ("C", "minor"): "5A", ("G", "minor"): "6A", ("D", "minor"): "7A",
    ("C", "major"): "8B", ("G", "major"): "9B", ("D", "major"): "10B", ("A", "major"): "11B", ("E", "major"): "12B", ("B", "major"): "1B",
    ("F#", "major"): "2B", ("C#", "major"): "3B", ("G#", "major"): "4B", ("D#", "major"): "5B", ("A#", "major"): "6B", ("F", "major"): "7B",
}
FLATS = {"Db": "C#", "Eb": "D#", "Gb": "F#", "Ab": "G#", "Bb": "A#"}


def ensure_models() -> None:
    MODELS.mkdir(parents=True, exist_ok=True)
    for path, ext in ((EFFNET_ONNX, ".onnx"), (EFFNET_META, ".json")):
        if not path.exists():
            urllib.request.urlretrieve(EFFNET_URL + ext, path)


def decode(path: str, sr: int = SR) -> np.ndarray:
    out = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(sr), "-f", "f32le", "-"], capture_output=True, check=True)
    return np.frombuffer(out.stdout, dtype=np.float32)


def windows(pcm: np.ndarray) -> tuple[list[np.ndarray], list[float]]:
    n, w = len(pcm), WINDOW_S * SR
    if n <= w:
        return [np.pad(pcm, (0, w - n))], [0.0]
    k = max(1, min(N_WINDOWS, int((n * (SPAN[1] - SPAN[0])) // w)))
    starts = [int(s) for s in np.linspace(n * SPAN[0], n * SPAN[1] - w, k)]
    return [pcm[s : s + w] for s in starts], [s / SR for s in starts]


# ---- worker process ----
_W = {}


def _init_worker() -> None:
    os.environ["OMP_NUM_THREADS"] = "1"
    import essentia
    import essentia.standard as es
    import onnxruntime as ort
    from transformers import ClapFeatureExtractor

    essentia.log.warningActive = False
    essentia.log.infoActive = False
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    so.inter_op_num_threads = 1
    _W["effnet"] = ort.InferenceSession(str(EFFNET_ONNX), so, providers=["CPUExecutionProvider"])
    _W["mel"] = es.TensorflowInputMusiCNN()
    _W["frames"] = es.FrameGenerator
    _W["es"] = es
    _W["key"] = es.KeyExtractor(profileType="edma", sampleRate=44100)
    _W["clapfx"] = ClapFeatureExtractor.from_pretrained(CLAP_MODEL)


def cpu_stage(track: tuple[str, str]) -> dict:
    import soxr

    tid, path = track
    try:
        pcm = decode(path)
        wins, offsets = windows(pcm)
        fx = _W["clapfx"](wins, sampling_rate=SR, return_tensors="np")
        x16 = soxr.resample(pcm, SR, 16000).astype(np.float32)
        mel = np.array([_W["mel"](f) for f in _W["frames"](x16, frameSize=512, hopSize=256, startFromZero=True)], dtype=np.float32)
        if len(mel) < 128:
            mel = np.pad(mel, ((0, 128 - len(mel)), (0, 0)))
        patches = np.stack([mel[i : i + 128] for i in range(0, len(mel) - 127, 62)])
        styles, emb = _W["effnet"].run(None, {"melspectrogram": patches})
        x44 = soxr.resample(pcm, SR, 44100).astype(np.float32)
        bpm = float(_W["es"].PercivalBpmEstimator(sampleRate=44100)(x44))  # fresh instance: the estimator keeps state between calls
        while bpm and bpm < 80:  # fold half-time guesses into club range
            bpm *= 2
        while bpm > 165:
            bpm /= 2
        key, scale, _strength = _W["key"](x44)
        return {
            "id": tid, "features": fx["input_features"].astype(np.float32), "is_longer": fx["is_longer"], "offsets": offsets,
            "effnet": emb.mean(0), "styles": styles.mean(0), "bpm": round(bpm, 1), "key": CAMELOT.get((FLATS.get(key, key), scale)),
        }
    except Exception as e:  # unreadable file etc.
        return {"id": tid, "error": f"{type(e).__name__}: {e}"}


# ---- main process ----
def analyze(limit: int | None = None, sample: bool = False, workers: int = 5, log=print) -> None:
    import torch
    from transformers import ClapModel

    ensure_models()
    con = db.connect()
    rows = con.execute(
        "SELECT id, path FROM tracks WHERE missing=0 AND duration <= ? AND id NOT IN (SELECT track_id FROM vectors WHERE kind='clap') "
        + ("ORDER BY random()" if sample else "ORDER BY path"), (MAX_DURATION,)
    ).fetchall()
    todo = [(r["id"], r["path"]) for r in rows][: limit or None]
    if not todo:
        log("analyze: nothing to do")
        return
    dev = "mps" if torch.backends.mps.is_available() else "cpu"
    log(f"analyze: {len(todo)} tracks · CLAP on {dev} · {workers} CPU workers")
    model = ClapModel.from_pretrained(CLAP_MODEL).to(dev).eval()

    def clap(batch: list[dict]) -> None:
        feats = np.concatenate([b["features"] for b in batch])
        longer = np.concatenate([b["is_longer"] for b in batch])
        with torch.no_grad():
            e = model.get_audio_features(input_features=torch.from_numpy(feats).to(dev), is_longer=torch.from_numpy(longer).to(dev))
        e = torch.nn.functional.normalize(e, dim=-1).cpu().numpy()
        k = 0
        for b in batch:
            w = e[k : k + len(b["offsets"])]
            k += len(b["offsets"])
            mean = w.mean(0)
            mean /= np.linalg.norm(mean)
            best = int(np.argmax(w @ mean))
            con.execute("UPDATE tracks SET bpm=?, key=?, audition=? WHERE id=?", (b["bpm"], b["key"], b["offsets"][best], b["id"]))
            for kind, v in (("clap", mean), ("effnet", b["effnet"]), ("styles", b["styles"])):
                con.execute("INSERT OR REPLACE INTO vectors VALUES (?,?,?)", (b["id"], kind, db.to_blob(v)))
            for i, (off, v) in enumerate(zip(b["offsets"], w)):
                con.execute("INSERT OR REPLACE INTO clap_windows VALUES (?,?,?,?)", (b["id"], i, off, db.to_blob(v)))

    t0, done, failed, batch = time.time(), 0, 0, []
    def results(ex):
        """Bounded in-flight work (ex.map would queue every track and buffer finished results)."""
        pending, it = set(), iter(todo)
        for t in itertools.islice(it, workers * 2):
            pending.add(ex.submit(cpu_stage, t))
        while pending:
            finished, pending = wait(pending, return_when=FIRST_COMPLETED)
            for f in finished:
                nxt = next(it, None)
                if nxt:
                    pending.add(ex.submit(cpu_stage, nxt))
                yield f.result()

    with ProcessPoolExecutor(workers, initializer=_init_worker) as ex:
        for r in results(ex):
            if "error" in r:
                failed += 1
                log(f"  ! {r['id']}: {r['error']}")
                continue
            batch.append(r)
            if len(batch) >= 8:
                clap(batch)
                done += len(batch)
                batch = []
                if done % 48 == 0:
                    con.commit()
                    rate = done / (time.time() - t0)
                    log(f"  {done}/{len(todo)} · {rate:.1f} tracks/s · eta {(len(todo) - done) / rate / 60:.0f} min")
        if batch:
            clap(batch)
            done += len(batch)
    con.commit()
    log(f"analyze: {done} done, {failed} failed in {(time.time() - t0) / 60:.1f} min")


def style_names() -> list[str]:
    return json.loads(EFFNET_META.read_text())["classes"]
