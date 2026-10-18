> **Status (2026-10-17):** Phases 0–5 implemented (`src/summit/`). See README.md for usage. Sections below are the original plan; the Decisions table reflects what was built.

# Summit: Implementation Plan

A local, interactive map of the music library, grouped by how tracks sound.
Exports playlists to rekordbox.

Items marked **[verify]** are assumptions to confirm during the Day 1 spike.

---

## Decisions

| Topic | Decision |
|---|---|
| Library scope | `~/Music/Music/Media.localized` only (~3,900 tracks, 82 GB; mp3/m4a/aiff/wav). Only files 60 s – 12 min: DJ mixes / recordings are excluded |
| Embeddings | **CLAP** (`laion/larger_clap_music_and_speech` via HF `transformers` 4.x; the `larger_clap_music` HF conversion has a collapsed text tower) **and Discogs-EffNet** (ONNX + Essentia mel, no TensorFlow), both computed from the start. Plus BPM (Percival) and key (EDMA profile → Camelot) from Essentia |
| Segments | ~6 × 10 s windows from the middle 70% of each track; store every window, average for the map |
| Track identity | Content hash (file size + sha1 of first 4 MB of audio), not the path |
| Database | Summit's own SQLite (`data/summit.db`); vectors stored as float32 BLOBs. Never reads or writes rekordbox's database |
| Clustering | UMAP (cosine) → 10D for HDBSCAN (`sklearn.cluster.HDBSCAN`), 2D for display |
| Shell | Native macOS window via **pywebview** (WebKit), so AIFF and ALAC play natively. Python backend runs in-process. Package as Summit.app later |
| Frontend | FastAPI + static HTML/JS using Plotly `scattergl` (no build step). Theme: "cyber-lite" palette from `future-city.jpeg` |
| Playback | Original files streamed with HTTP Range support; full transport (play/pause/next/prev) with a context queue. Fallback for non-WebKit browsers: AIFF→WAV byte-swap, ALAC→FLAC via ffmpeg |
| Export | rekordbox XML (`summit/` playlist folder) **and** `.m3u8` |
| Environment | `uv`, Python 3.11, Apple GPU (MPS) for CLAP |
| Location | `~/summit` · app on port **8766** (MusicSync uses 8765) |
| Styling | Night City Terminal, shared with MusicSync (see DESIGN.md) |

---

## Project layout

```
summit/
  pyproject.toml         # uv project, Python 3.11 (package lives in src/summit/)
  mockups/               # UI mockups + app.py (pywebview + range audio server)
  config.toml            # library root, segment count, paths
  vibes.txt              # vibe vocabulary for auto-labels
  summit/
    __main__.py          # CLI: python -m summit <cmd>
    db.py                # SQLite schema + helpers
    scan.py              # step 1
    audio.py             # ffmpeg decode, windowing, preview clips
    embed_clap.py        # step 2a
    embed_effnet.py      # step 2b
    layout.py            # step 3
    label.py             # step 4
    server.py            # step 5 backend
    train_vibe.py        # step 6
    export.py            # step 7
  web/
    index.html  app.js  style.css
  data/                  # gitignored: summit.db, emb/, previews/, exports/
  models/                # gitignored: downloaded model weights
```

CLI commands: `scan`, `embed [--limit N] [--model clap|effnet|all]`, `previews`, `layout`, `label`, `serve`, `export`.
Every step is incremental: it only processes tracks that are missing output.

---

## Data model (SQLite)

```sql
tracks(id TEXT PK,            -- content hash
       path TEXT UNIQUE, artist, title, album, genre,
       duration REAL, format TEXT, mtime REAL,
       added_at, missing INTEGER DEFAULT 0)

embeddings(track_id, model TEXT,      -- 'clap' | 'effnet'
           row INTEGER,               -- row index into data/emb/<model>_track.npy
           n_segments INTEGER, computed_at,
           PRIMARY KEY(track_id, model))

layouts(space TEXT,                   -- 'clap' | 'effnet' | 'combined'
        track_id, x REAL, y REAL, cluster INTEGER,
        PRIMARY KEY(space, track_id))

clusters(space, cluster INTEGER, label TEXT, label_source TEXT,  -- 'auto' | 'user'
         PRIMARY KEY(space, cluster))

vibes(id PK, name TEXT, created_at)           -- user-defined vibes (step 6)
vibe_examples(vibe_id, track_id, positive INTEGER)
playlists(id PK, name, created_at)
playlist_tracks(playlist_id, track_id, position)
```

Embedding files in `data/emb/`:
- `clap_track.npy`: N × 512, L2-normalised mean of the windows
- `clap_segments.npy` + `clap_segments_index.npy`: every window, mapped to its track
- `effnet_track.npy`: N × 1280 **[verify dim]**
- `effnet_styles.npy`: N × 400 Discogs style scores, plus `effnet_styles_labels.json`

---

## Pipeline

### 1. Scan
- Walk the library root for audio files. Skip files under 60 s; that drops stray samples and sound effects.
- Read tags with `mutagen`, which handles ID3 in mp3/aiff/wav as well as MP4 atoms. Fall back to the filename when tags are missing.
- Upsert into `tracks` by content hash, so a moved file updates its path instead of becoming a new track. If a file disappears, set `missing=1` rather than deleting the row.

### 2. Embed
- **Decode once per track:** run ffmpeg to get mono float32 PCM at 48 kHz, and resample to 16 kHz for EffNet. Decoding uses a pool of CPU workers while the GPU runs the models.
- **Windows:** 6 × 10 s, evenly spaced across 15–85% of the track's duration. Short tracks get fewer windows.
- **CLAP:** `ClapModel.get_audio_features` in batches on MPS. Store each window's embedding and the normalised mean.
- **Discogs-EffNet:** compute Essentia's mel patches, then run `discogs-effnet-bsdynamic` through `onnxruntime`. Average the embeddings and style scores across patches. **[verify: ONNX model availability; whether the `essentia` pip wheel (no TensorFlow) installs on arm64 for the mel preprocessing. Fallback: run Essentia in a Linux Docker container just for this step.]**
- **Audition point:** store the offset of the window whose CLAP embedding is closest to the track's mean; ⌥+hover auditions from there.
- Checkpoint every ~200 tracks so an interrupted run can resume.

### 3. Layout and clusters
- For each space (`clap`, `effnet`, `combined` = both normalised embeddings concatenated):
  - UMAP 2D for display: cosine metric, `n_neighbors≈15`, `min_dist≈0.1`.
  - UMAP 10D (`min_dist=0`), then HDBSCAN (`min_cluster_size≈15`).
- Fix the random seed so the map stays stable between runs. Re-running after adding tracks re-lays out everything, which is fine at this size.

### 4. Auto-label clusters
- Embed each phrase in `vibes.txt` with CLAP's text encoder.
- For each cluster centroid, take the top 3 phrases, plus the top 2 Discogs styles averaged over the cluster.
- A label you edit by hand is stored as `label_source='user'` and is never overwritten.

### 5. Map UI
- **Endpoints:** `GET /api/tracks`, `GET /api/layout/{space}`, `GET /api/neighbors/{id}?space=&k=`, `GET /api/search?q=` (CLAP text → cosine over track embeddings), `GET /api/overlay/{name}` (style or mood score per track), `GET /audio/{id}` (Range-capable), plus `POST` endpoints for playlists, vibes, labels and flows.
- **UI conventions (from mockup review):** no mode buttons. Drag always pans, scroll zooms, Ctrl+drag lassos (⇧ adds), Esc clears. Every side and bottom panel is resizable by dragging. Click plays, ⌥+hover auditions.
- **Page features:**
  - Switch between spaces (CLAP / EffNet / combined).
  - Colour by cluster, Discogs style, or vibe score.
  - Hover: play the preview clip and show artist and title.
  - Click: highlight the 10 nearest neighbours in a side panel.
  - Lasso: save the selection as a playlist, or as examples of a vibe.
  - Search box: dim everything except the top matches.
  - **Flow builder:** click to place ordered stages; a corridor of adjustable radius around the route pulls in tracks; target length sets how many. Insert/drag/delete stages with undo/redo. Bottom panel: colour-coded set timeline with a BPM line, plus the play-order list. Saved flows become playlists.

### 6. Personal vibes
- Lasso some tracks, name the vibe, and Summit stores them as positive examples. Negatives are random tracks from far-away clusters.
- Train logistic regression on the CLAP + EffNet embeddings. Its score becomes a colour overlay and a "suggest more" list.
- In the panel, you can accept or reject suggestions; each decision is added to the examples.

### 7. Export
- **rekordbox XML:** write `data/exports/summit.xml` in the `DJ_PLAYLISTS` format. `COLLECTION` holds only the exported tracks, each with `Location="file://localhost/<url-encoded path>"`. `PLAYLISTS` holds a `summit` folder node containing one playlist node per Summit playlist.
  - To use it once: in rekordbox, open Preferences → Advanced → rekordbox xml and point it at the file. The playlists then appear under "rekordbox xml" in the tree, and you drag them into your collection.
  - Because both apps read the same files, tracks already in rekordbox are matched by path and keep their cues and grids. **[verify on one playlist]**
- **m3u8:** `data/exports/m3u8/<name>.m3u8` with absolute paths and `#EXTINF` lines.

---

## Phases

| Phase | Work | Done when |
|---|---|---|
| **0. Setup** | Install `uv` (via brew), scaffold the project, pin dependencies, download both models | `python -m summit --help` runs; CLAP loads on MPS; EffNet ONNX runs on one file |
| **1. Sample** | Scan; embed + previews on 500 random tracks; layout in all 3 spaces; a quick throwaway Plotly HTML for each | You've looked at the three maps and decided whether CLAP, EffNet or combined groups tracks the way you hear them. Throughput measured. |
| **2. Full run** | Embed all ~3,900 tracks, unattended | All tracks embedded; runtime noted |
| **3. Map UI** | FastAPI server and the real page: spaces, colours, hover preview, neighbours, search | Usable map at `localhost:8000` |
| **4. Playlists + export** | Lasso to playlist, XML + m3u8 export | A Summit playlist imports into rekordbox and matches existing tracks |
| **5. Labels + vibes** | Auto-labels, personal vibe classifier, accept/reject loop | Clusters have names; at least one custom vibe trained |

## v2: Shared tags with rekordbox

Goal: one tag vocabulary that Summit and rekordbox both use. Summit fills in
tags for the mostly untagged library, and rekordbox can search and filter by them.

### Tag model in Summit
```sql
tag_categories(id PK, name)            -- mirrors rekordbox My Tag categories
tags(id PK, category_id, name, vibe_id NULL)   -- a tag can be backed by a trained vibe (step 6)
track_tags(track_id, tag_id,
           source TEXT,                -- 'user' | 'model' | 'rekordbox'
           score REAL, status TEXT,    -- 'suggested' | 'confirmed' | 'rejected'
           PRIMARY KEY(track_id, tag_id))
```
- **Where suggestions come from:** vibe classifiers (step 6), Discogs styles above a threshold, and CLAP text matches.
- **Review:** suggested tags show up on the map. You confirm or reject them one at a time or for a whole lasso selection. Only confirmed tags go to rekordbox, unless you set a per-tag auto-confirm threshold.

### Getting tags into rekordbox
Installed: rekordbox 7.2.18. Its library is `~/Library/Pioneer/rekordbox/master.db`, an encrypted SQLite file.

1. **Primary: native My Tags via `pyrekordbox` writing to `master.db`.**
   - My Tags are rekordbox's own tag system: filterable in the browser, shown in a tag column, and carried to USB exports.
   - `pyrekordbox` can decrypt `master.db` and has tables for My Tags and the track-to-tag links.
   - **Sync rules:** rekordbox must be closed. Back up `master.db` first. Show a dry-run diff before writing. Summit only touches tags in categories it manages.
   - **[verify: pyrekordbox read/write support for rekordbox 7.2; how to obtain the database key on this version]**
2. **Fallback: hashtags in the file's Comments field** (`#deep #rolling #dark`) via mutagen.
   - rekordbox's search covers Comments, and the tags travel with the file.
   - Downsides: Summit rewrites the audio files' tags, rekordbox needs "Reload Tag" to see changes **[verify]**, and WAV tag support is spotty.
   - Only enable this if option 1 fails, and back up files before the first write.
3. **Not used: rekordbox XML.** It's the right tool for playlists, but it probably doesn't carry My Tags, and re-importing doesn't update metadata on tracks already in the collection **[verify]**.

### Two-way sync
- **Read:** pull rekordbox My Tags and the track list from `master.db` (read-only). Tags you add in rekordbox come into Summit as `source='rekordbox'` and confirmed, and also become training examples for the vibe classifiers.
- **Matching:** rekordbox tracks are matched to Summit tracks by file path, falling back to artist + title + duration.
- **Conflicts:** if a tag changed on both sides since the last sync, Summit lists it and doesn't write it.
- **Command:** `python -m summit sync-rekordbox [--dry-run] [--pull-only]`.

### v2 phases
| Phase | Work | Done when |
|---|---|---|
| **6a. Spike** | Read-only pyrekordbox access to `master.db`; list My Tag categories and tag links; try writing one tag to a copy of the database | Confirms option 1 is viable, or switches to option 2 |
| **6b. Tag model + review UI** | Tables, suggestions, confirm/reject on the map | Tags confirmed on ≥100 tracks |
| **6c. Sync** | Pull, diff, push with backup | Tags show up in rekordbox's My Tag filter; a tag added in rekordbox appears in Summit |

## Risks
- **CLAP may not separate electronic subgenres well.** That's the reason for EffNet and the "combined" space; Phase 1 decides which one is the default.
- **Essentia preprocessing on arm64.** Fallback is Docker. Last resort is reimplementing the mel frontend in librosa and checking it against Essentia's reference output.
- **rekordbox path matching.** Paths with unusual Unicode characters may not match. Test with tracks that have accented names.
- **Writing to rekordbox's database (v2).** It's undocumented, and a rekordbox update could change it. Mitigations: back up every time, close rekordbox first, preview a dry-run diff, write only what's needed.
- **Licensing.** The Discogs-EffNet weights are CC BY-NC-SA, which is fine for personal use. Don't publish the models or derived scores commercially.
