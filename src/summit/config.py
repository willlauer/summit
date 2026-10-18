"""Paths and tunables. Everything Summit writes lives under data/."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LIBRARY = Path.home() / "Music/Music/Media.localized"
DATA = ROOT / "data"
MODELS = ROOT / "models"
EXPORTS = DATA / "exports"
DB_PATH = DATA / "summit.db"
WEB = Path(__file__).with_name("web")

AUDIO_EXTS = {".mp3", ".m4a", ".aiff", ".aif", ".wav", ".flac"}
MIN_DURATION = 60  # seconds; shorter files are samples / stingers
MAX_DURATION = 12 * 60  # longer files are DJ mixes / recordings, not tracks

# Analysis
SR = 48_000  # CLAP sample rate
N_WINDOWS = 6  # CLAP windows per track
WINDOW_S = 10
SPAN = (0.15, 0.85)  # skip DJ intros/outros
# laion/larger_clap_music has a broken HF text tower (all prompts embed identically); this sibling is healthy.
CLAP_MODEL = "laion/larger_clap_music_and_speech"
EFFNET_URL = "https://essentia.upf.edu/models/feature-extractors/discogs-effnet/discogs-effnet-bsdynamic-1"
EFFNET_ONNX = MODELS / "discogs-effnet-bsdynamic-1.onnx"
EFFNET_META = MODELS / "discogs-effnet-bsdynamic-1.json"

# Embedding spaces the map can show. Each combines one or more per-track vector kinds (rows in `vectors`), each
# L2-normalised and weighted equally. To try a new model: write its vectors under a new kind, add a space here,
# then `summit layout`. The app's space picker is built from this list.
SPACES = {
    "clap": {"label": "CLAP", "kinds": ["clap"]},
    "effnet": {"label": "EffNet", "kinds": ["effnet"]},
    "combined": {"label": "Combined", "kinds": ["clap", "effnet"]},
}
DEFAULT_SPACE = "combined"
TAG_SPACE = "combined"  # tag classifiers and vibes train on this space
UMAP_NEIGHBORS = 15
SEED = 42

# Tag vocabulary (mirrors rekordbox My Tag categories). Each tag is scored zero-shot with a CLAP text prompt
# until the user confirms enough examples to train it.
TAGS = {
    "Mood": {
        "dark": "dark, ominous, brooding electronic music",
        "warm": "warm, soulful, comforting music",
        "euphoric": "euphoric, uplifting, joyful dance music",
        "melancholic": "melancholic, sad, wistful music",
        "hypnotic": "hypnotic, repetitive, trance-inducing groove",
        "playful": "playful, bouncy, fun music",
    },
    "Energy": {
        "warm-up": "gentle, low-energy opening dj track",
        "builder": "building, rising energy dance track",
        "peak": "peak-time, high-energy, intense club track",
        "closer": "calm, emotional closing track",
    },
    "Texture": {
        "dubby": "dub techno with deep echoes and reverb chords",
        "acid": "acid squelchy 303 bassline",
        "vocal": "track with prominent singing vocals",
        "percussive": "percussion-driven track with lots of drums",
        "piano": "track with prominent piano",
        "lush pads": "lush atmospheric synth pads",
        "rolling bass": "rolling, driving bassline",
    },
    "Situation": {
        "late night": "late night, after-dark club music",
        "afterhours": "deep, hypnotic afterhours music",
        "daytime": "sunny daytime outdoor party music",
        "sunrise": "dreamy sunrise music",
    },
}
