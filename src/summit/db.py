"""SQLite store. Summit's own per-track database; never touches rekordbox's."""

import sqlite3
import time

import numpy as np

from .config import DATA, DB_PATH

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
CREATE TABLE IF NOT EXISTS layouts3d (  -- the same space projected to 3D, for the galaxy view
  space TEXT, track_id TEXT, x REAL, y REAL, z REAL,
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
    # Files scan rejected (not tracks) and tracks analyze couldn't decode: remembered so they don't count as pending forever.
    if not con.execute("SELECT 1 FROM sqlite_master WHERE name='skipped'").fetchone():
        con.execute("CREATE TABLE skipped (path TEXT PRIMARY KEY, size INTEGER, mtime REAL)")
        con.execute("CREATE TABLE failures (track_id TEXT PRIMARY KEY, error TEXT, at REAL)")
        con.commit()
    if not con.execute("SELECT 1 FROM sqlite_master WHERE name='layouts3d'").fetchone():
        con.execute("CREATE TABLE layouts3d (space TEXT, track_id TEXT, x REAL, y REAL, z REAL, PRIMARY KEY (space, track_id))")
        con.commit()
    # Optimistic concurrency for playlists edited from two devices: every write bumps the version.
    if "version" not in {r[1] for r in con.execute("PRAGMA table_info(playlists)")}:
        con.execute("ALTER TABLE playlists ADD COLUMN version INTEGER DEFAULT 0")
        con.commit()
    # Folders of playlists (nestable), mirrored as folders in the rekordbox export. A playlist sits in one folder or none.
    if not con.execute("SELECT 1 FROM sqlite_master WHERE name='folders'").fetchone():
        con.execute("CREATE TABLE folders (id INTEGER PRIMARY KEY, name TEXT, parent_id INTEGER, created_at REAL, updated_at REAL)")
        con.commit()
    if "folder_id" not in {r[1] for r in con.execute("PRAGMA table_info(playlists)")}:
        con.execute("ALTER TABLE playlists ADD COLUMN folder_id INTEGER")
        con.commit()
    # rekordbox (rekordbox.py): its track info from a collection export.
    if not con.execute("SELECT 1 FROM sqlite_master WHERE name='rb_tracks'").fetchone():
        con.execute("CREATE TABLE rb_tracks (track_id TEXT PRIMARY KEY, attrs TEXT)")
        con.execute("CREATE TABLE rb_meta (key TEXT PRIMARY KEY, val TEXT)")
        con.commit()
    # Hot cues are gone: their edits, player offsets, and rekordbox's grids and marks.
    if con.execute("SELECT 1 FROM sqlite_master WHERE name IN ('cue_edits', 'rb_offsets')").fetchone():
        con.execute("DROP TABLE IF EXISTS cue_edits")
        con.execute("DROP TABLE IF EXISTS rb_offsets")
        con.commit()
    for col in ("tempo", "marks"):
        if col in {r[1] for r in con.execute("PRAGMA table_info(rb_tracks)")}:
            con.execute(f"ALTER TABLE rb_tracks DROP COLUMN {col}")
            con.commit()
    # Listening history (one row per real listen: 30 s, or half a short track), for Discover.
    if not con.execute("SELECT 1 FROM sqlite_master WHERE name='plays'").fetchone():
        con.execute("CREATE TABLE plays (track_id TEXT, at REAL, client TEXT, secs REAL)")
        con.execute("CREATE INDEX plays_at ON plays (at)")
        con.commit()
    for table in ("layouts", "clusters"):
        cols = {r[1] for r in con.execute(f"PRAGMA table_info({table})")}
        if "family" not in cols:
            con.execute(f"ALTER TABLE {table} ADD COLUMN family INTEGER")
            con.commit()
    # Tag taxonomy (taxonomy.py): genres sit in a family. Known data: raw lookups per source (kept so the maps can
    # change without refetching), and the evidence they give for each tag (file | discogs | lastfm | rule).
    if "parent" not in {r[1] for r in con.execute("PRAGMA table_info(tags)")}:
        con.execute("ALTER TABLE tags ADD COLUMN parent TEXT")
        con.commit()
    if not con.execute("SELECT 1 FROM sqlite_master WHERE name='ext_meta'").fetchone():
        con.execute("CREATE TABLE ext_meta (track_id TEXT, source TEXT, data TEXT, fetched_at REAL, PRIMARY KEY (track_id, source))")
        con.execute("CREATE TABLE tag_evidence (track_id TEXT, tag_id INTEGER, source TEXT, weight REAL, PRIMARY KEY (track_id, tag_id, source))")
        con.commit()
    # Release year from the file's tags (Era); NULL = not read yet, 0 = the file has none.
    if "year" not in {r[1] for r in con.execute("PRAGMA table_info(tracks)")}:
        con.execute("ALTER TABLE tracks ADD COLUMN year INTEGER")
        con.commit()


def seed_tags(con: sqlite3.Connection) -> None:
    """Make the tags table match taxonomy.py. Tags from older vocabularies are dropped with their marks; user-made
    Vibe tags are kept."""
    from .taxonomy import rows

    want = rows()
    have = {tuple(r) for r in con.execute("SELECT category, name, prompt, parent FROM tags WHERE category != 'Vibe'")}
    if have == set(want):
        return  # nothing to write; avoids taking a write lock on every connect
    names = {w[1] for w in want}
    stale = [r[0] for r in con.execute("SELECT id, name FROM tags WHERE category != 'Vibe'") if r[1] not in names]
    for table in ("track_tags", "tag_evidence"):
        con.executemany(f"DELETE FROM {table} WHERE tag_id=?", [(i,) for i in stale])
    con.executemany("DELETE FROM tags WHERE id=?", [(i,) for i in stale])
    con.executemany(
        "INSERT INTO tags(category, name, prompt, parent) VALUES (?,?,?,?) "
        "ON CONFLICT(name) DO UPDATE SET category=excluded.category, prompt=excluded.prompt, parent=excluded.parent", want)
    con.commit()


def purge(con: sqlite3.Connection, ids: list[str]) -> None:
    """Remove tracks and everything derived from them."""
    for table, col in (("vectors", "track_id"), ("clap_windows", "track_id"), ("layouts", "track_id"), ("layouts3d", "track_id"),
                       ("track_tags", "track_id"), ("tag_evidence", "track_id"), ("ext_meta", "track_id"), ("playlist_tracks", "track_id"), ("likes", "track_id"), ("failures", "track_id"), ("rb_tracks", "track_id"), ("plays", "track_id"),
                       ("tracks", "id")):
        con.executemany(f"DELETE FROM {table} WHERE {col}=?", [(i,) for i in ids])


def to_blob(v: np.ndarray) -> bytes:
    return np.asarray(v, dtype=np.float32).tobytes()


def from_blob(b: bytes) -> np.ndarray:
    return np.frombuffer(b, dtype=np.float32)


def now() -> float:
    return time.time()
