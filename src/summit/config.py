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

# Tag taxonomy lives in taxonomy.py. API keys for the known-data lookups (summit tags fetch, Tools › Tags):
# {"discogs": "<personal access token>", "lastfm": "<api key>"}. data/ is git-ignored.
KEYS_FILE = DATA / "keys.json"
