# Summit

A local map of the music library, grouped by how tracks *sound*. Native macOS window (pywebview / WebKit, so AIFF and
ALAC play), FastAPI backend, SQLite store. Styling matches MusicSync (see `DESIGN.md`).

```
uv run summit scan        # index ~/Music/Music/Media.localized (seconds; incremental)
uv run summit analyze     # CLAP + Discogs-EffNet embeddings, styles, BPM, key (~1.7 tracks/s; resumable)
uv run summit layout      # UMAP maps + HDBSCAN clusters for clap / effnet / combined, then auto-labels
uv run summit app         # open the app (or --browser to serve at http://127.0.0.1:8766)
uv run summit export      # rekordbox XML + m3u8 for all playlists → data/exports/
uv run summit status
```

After adding music: `scan`, `analyze`, `layout`, then reopen the app. Files shorter than 60 s or longer than 12 min
(samples, DJ mixes, recordings) are ignored; `scan` removes any that were indexed before.

**Modes:** Map (explore, playlists, cluster names; 3D toggle) · Tag (bulk tagging) · Flow (route a set through the map) ·
Tree (grow a playlist by following "sounds like").

**Organisation:** each map has *families* (where the embedding has real density gaps) split into *sub-clusters*
only where a family has stable internal structure — no fixed counts. Names come from the most distinctive Discogs
styles (sub-clusters contrast with their siblings); rename either level in the Map panel. Spaces (CLAP / EffNet /
Combined) are defined in `SPACES` in `config.py`: to try another embedding model, store its vectors under a new kind,
add a space, and run `summit layout`.
**Keys:** space play/pause · ⇧← ⇧→ prev/next · ⌥+hover audition · ctrl+drag lasso (⇧ adds) · esc clear ·
⌘Z/⇧⌘Z undo in Flow · ⌘K add stage in Flow · **ctrl+alt+shift+D toggles help tips**.
**Header toggles:** Labels › Genres / Subgenres (map labels; both on = follow zoom) · EN / 日本語 (interface language).

**Tags** start as zero-shot CLAP prompts (`TAGS` in `src/summit/config.py`); once a tag has 5 confirmed tracks it
retrains as a classifier on your picks. "Teach a vibe" turns a selection into a new trained tag.

**rekordbox:** Preferences → Advanced → rekordbox xml → Imported Library = `data/exports/summit.xml`; playlists appear
under "rekordbox xml" in the tree (folder `summit/`). Summit never touches rekordbox's own database.

**Phone (Android, `android/`):** the same library, map (OpenGL, 2D/3D), search, playlists and player, over the API.
Run the server in the background with `uv run summit service install` (the desktop window then attaches to it), share
it on your tailnet with `tailscale serve --bg 8766`, and enter the `https://<mac>.<tailnet>.ts.net` address under
Library → ⚙ in the app. Lossless tracks are transcoded to AAC on first play (`summit transcode` does them all up
front). Build and install with `cd android && ./gradlew installDebug` (Android Studio's JDK and SDK; phone on USB).
Icons for both apps come from `scripts/make_icon.py`; `summit bundle` builds `~/Applications/Summit.app`.

Data lives in `data/` (gitignored); models in `models/` (EffNet ONNX downloads automatically; CLAP via Hugging Face cache).
`mockups/` holds the throwaway UI mockups from the design phase.
