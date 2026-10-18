"""SQLite store. Summit's own per-track database; never touches rekordbox's."""

import sqlite3
import time

import numpy as np

from .config import DATA, DB_PATH, TAGS

SCHEMA = """
CREATE TABLE IF NOT EXISTS tracks (
  id TEXT PRIMARY KEY,           -- stable id assigned at first sight
  path TEXT UNIQUE NOT NULL,
  hash TEXT,                     -- sha1 of a mid-file chunk, used to detect moved files
  artist TEXT, title TEXT, album TEXT, genre TEXT,
  duration REAL, format TEXT, size INTEGER, mtime REAL,
  bpm REAL, key TEXT,            -- Camelot notation, e.g. 8A
  audition REAL,                 -- seconds: start of the most representative CLAP window
  added_at REAL, missing INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS vectors (
  track_id TEXT, kind TEXT,      -- clap | effnet | styles
  vec BLOB NOT NULL,
  PRIMARY KEY (track_id, kind)
);
CREATE TABLE IF NOT EXISTS clap_windows (
  track_id TEXT, idx INTEGER, offset REAL, vec BLOB NOT NULL,
  PRIMARY KEY (track_id, idx)
);
CREATE TABLE IF NOT EXISTS layouts (
  space TEXT, track_id TEXT, x REAL, y REAL, cluster INTEGER,
  PRIMARY KEY (space, track_id)
);
CREATE TABLE IF NOT EXISTS clusters (
  space TEXT, cluster INTEGER, label TEXT, label_source TEXT DEFAULT 'auto', styles TEXT, vibes TEXT,
  PRIMARY KEY (space, cluster)
);
CREATE TABLE IF NOT EXISTS families (
  space TEXT, family INTEGER, label TEXT, label_source TEXT DEFAULT 'auto', styles TEXT, vibes TEXT,
  PRIMARY KEY (space, family)
);
CREATE TABLE IF NOT EXISTS tags (
  id INTEGER PRIMARY KEY, category TEXT, name TEXT UNIQUE, prompt TEXT
);
CREATE TABLE IF NOT EXISTS track_tags (
  track_id TEXT, tag_id INTEGER, status TEXT,   -- confirmed | rejected
  source TEXT, updated_at REAL,
  PRIMARY KEY (track_id, tag_id)
);
CREATE TABLE IF NOT EXISTS playlists (
  id INTEGER PRIMARY KEY, name TEXT, kind TEXT DEFAULT 'playlist', data TEXT, created_at REAL, updated_at REAL
);
CREATE TABLE IF NOT EXISTS playlist_tracks (
  playlist_id INTEGER, position INTEGER, track_id TEXT,
  PRIMARY KEY (playlist_id, position)
);
"""


def connect() -> sqlite3.Connection:
    DATA.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(DB_PATH, check_same_thread=False, timeout=60)  # wait out a long analyze transaction
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA journal_mode=WAL")
    if not con.execute("SELECT 1 FROM sqlite_master WHERE name='families'").fetchone():
        con.executescript(SCHEMA)
    migrate(con)
    seed_tags(con)
    return con


def migrate(con: sqlite3.Connection) -> None:
    """Two-level organisation: tracks belong to a sub-cluster (`cluster`) inside a family. Likes."""
    if not con.execute("SELECT 1 FROM sqlite_master WHERE name='likes'").fetchone():
        con.execute("CREATE TABLE likes (track_id TEXT PRIMARY KEY, created_at REAL)")
        con.commit()
    for table in ("layouts", "clusters"):
        cols = {r[1] for r in con.execute(f"PRAGMA table_info({table})")}
        if "family" not in cols:
            con.execute(f"ALTER TABLE {table} ADD COLUMN family INTEGER")
            con.commit()


def seed_tags(con: sqlite3.Connection) -> None:
    have = {(r[0], r[1], r[2]) for r in con.execute("SELECT category, name, prompt FROM tags")}
    if all((c, n, p) in have for c, ts in TAGS.items() for n, p in ts.items()):
        return  # nothing to write; avoids taking a write lock on every connect
    for cat, tags in TAGS.items():
        for name, prompt in tags.items():
            con.execute(
                "INSERT INTO tags(category, name, prompt) VALUES (?,?,?) ON CONFLICT(name) DO UPDATE SET prompt=excluded.prompt",
                (cat, name, prompt),
            )
    con.commit()


def purge(con: sqlite3.Connection, ids: list[str]) -> None:
    """Remove tracks and everything derived from them."""
    for table, col in (("vectors", "track_id"), ("clap_windows", "track_id"), ("layouts", "track_id"),
                       ("track_tags", "track_id"), ("playlist_tracks", "track_id"), ("likes", "track_id"), ("tracks", "id")):
        con.executemany(f"DELETE FROM {table} WHERE {col}=?", [(i,) for i in ids])


def to_blob(v: np.ndarray) -> bytes:
    return np.asarray(v, dtype=np.float32).tobytes()


def from_blob(b: bytes) -> np.ndarray:
    return np.frombuffer(b, dtype=np.float32)


def now() -> float:
    return time.time()
