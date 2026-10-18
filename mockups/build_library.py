"""Index the real music library for the UI mockups: tags + duration via ffprobe -> library.json."""

import json
import subprocess
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path.home() / "Music/Music/Media.localized"
EXTS = {".mp3", ".m4a", ".aiff", ".aif", ".wav", ".flac"}
OUT = Path(__file__).with_name("library.json")


def probe(path: Path) -> dict | None:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration:format_tags=artist,title,album",
         "-of", "json", str(path)],
        capture_output=True, text=True,
    )
    if r.returncode:
        return None
    fmt = json.loads(r.stdout).get("format", {})
    tags = {k.lower(): v for k, v in fmt.get("tags", {}).items()}
    dur = float(fmt.get("duration", 0))
    if dur < 60:  # samples, stingers, broken files
        return None
    return {
        "path": str(path),
        "artist": tags.get("artist") or path.parent.parent.name,
        "title": tags.get("title") or path.stem,
        "album": tags.get("album") or path.parent.name,
        "dur": round(dur),
        "ext": path.suffix.lower().lstrip("."),
    }


def main() -> None:
    files = sorted(p for p in ROOT.rglob("*") if p.suffix.lower() in EXTS)
    with ThreadPoolExecutor(12) as ex:
        tracks = [t for t in ex.map(probe, files) if t]
    OUT.write_text(json.dumps(tracks))
    print(f"{len(tracks)} tracks (of {len(files)} files) -> {OUT}")


if __name__ == "__main__":
    main()
