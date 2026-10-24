"""Step 7: playlists out. rekordbox XML (a `summit/` folder of playlists, with Summit's folders nested inside) plus one
.m3u8 per playlist, in matching subdirectories.

In rekordbox: Preferences → Advanced → rekordbox xml → point "Imported Library" at data/exports/summit.xml,
then drag playlists or whole folders from the "rekordbox xml" tree into your collection. Tracks match by file path.
"""

import re
import shutil
from urllib.parse import quote
from xml.sax.saxutils import quoteattr

from .config import EXPORTS

KIND = {"mp3": "MP3 File", "m4a": "M4A File", "aiff": "AIFF File", "aif": "AIFF File", "wav": "WAV File", "flac": "FLAC File"}


def safe(name: str | None, fallback: str) -> str:
    """A file or directory name from a playlist / folder name."""
    return re.sub(r"[^\w\- ]+", "_", name or "").strip() or fallback


def location(path: str) -> str:
    return "file://localhost" + quote(path, safe="/")


def export_all(con) -> dict:
    """Folders in Summit become folders inside summit/ (and subdirectories of m3u8/); Liked is the first top-level playlist."""
    pls = [dict(r) for r in con.execute("SELECT id, name, folder_id FROM playlists ORDER BY name COLLATE NOCASE")]
    folders = [dict(r) for r in con.execute("SELECT id, name, parent_id FROM folders ORDER BY name COLLATE NOCASE")]
    if con.execute("SELECT 1 FROM likes LIMIT 1").fetchone():
        pls.insert(0, {"id": None, "name": "Liked", "folder_id": None})
    known = {f["id"] for f in folders}
    parent = lambda fid: fid if fid in known else None  # a dangling folder id counts as the top level
    EXPORTS.mkdir(parents=True, exist_ok=True)
    m3u_root = EXPORTS / "m3u8"
    if m3u_root.exists():
        shutil.rmtree(m3u_root)  # rewritten whole, so renamed / moved / deleted playlists don't leave stale copies
    tracks, rows_of = {}, {}
    for pl in pls:
        rows = con.execute(
            "SELECT t.* FROM playlist_tracks p JOIN tracks t ON t.id=p.track_id WHERE p.playlist_id=? ORDER BY p.position", (pl["id"],)
        ).fetchall() if pl["id"] is not None else con.execute(
            "SELECT t.* FROM likes l JOIN tracks t ON t.id=l.track_id ORDER BY l.created_at DESC").fetchall()
        rows_of[pl["id"]] = rows
        for r in rows:
            tracks.setdefault(r["id"], (len(tracks) + 1, r))

    def node(fid, depth: int, path):
        """XML lines for one folder's contents (subfolders, then playlists), writing its .m3u8 files under path."""
        subs = [f for f in folders if parent(f["parent_id"]) == fid and f["id"] != fid]
        lists = [pl for pl in pls if parent(pl["folder_id"]) == fid]
        pad, out = "  " * depth, []
        for f in subs:
            inner = node(f["id"], depth + 1, path / safe(f["name"], f"folder-{f['id']}"))
            out.append(f'{pad}<NODE Type="0" Name={quoteattr(f["name"] or "")} Count="{inner[0]}">')
            out += inner[1] + [f"{pad}</NODE>"]
        for pl in lists:
            rows = rows_of[pl["id"]]
            path.mkdir(parents=True, exist_ok=True)
            m3u = ["#EXTM3U"] + [f"#EXTINF:{int(r['duration'] or 0)},{r['artist']} - {r['title']}\n{r['path']}" for r in rows]
            (path / f"{safe(pl['name'], 'playlist-' + str(pl['id']))}.m3u8").write_text("\n".join(m3u) + "\n", encoding="utf-8")
            out.append(f'{pad}<NODE Name={quoteattr(pl["name"] or "")} Type="1" KeyType="0" Entries="{len(rows)}">')
            out += [f'{pad}  <TRACK Key="{tracks[r["id"]][0]}"/>' for r in rows]
            out.append(f"{pad}</NODE>")
        return len(subs) + len(lists), out

    count, body = node(None, 4, m3u_root)
    m3u_root.mkdir(exist_ok=True)
    x = ['<?xml version="1.0" encoding="UTF-8"?>', '<DJ_PLAYLISTS Version="1.0.0">', '  <PRODUCT Name="Summit" Version="0.1" Company=""/>',
         f'  <COLLECTION Entries="{len(tracks)}">']
    for tid, r in tracks.values():
        x.append(f'    <TRACK TrackID="{tid}" Name={quoteattr(r["title"] or "")} Artist={quoteattr(r["artist"] or "")} '
                 f'Album={quoteattr(r["album"] or "")} Kind="{KIND.get(r["format"], "")}" TotalTime="{int(r["duration"] or 0)}" '
                 f'Location={quoteattr(location(r["path"]))}/>')
    x += ["  </COLLECTION>", "  <PLAYLISTS>", '    <NODE Type="0" Name="ROOT" Count="1">', f'      <NODE Type="0" Name="summit" Count="{count}">']
    x += body + ["      </NODE>", "    </NODE>", "  </PLAYLISTS>", "</DJ_PLAYLISTS>"]
    (EXPORTS / "summit.xml").write_text("\n".join(x) + "\n", encoding="utf-8")
    return {"xml": str(EXPORTS / "summit.xml"), "m3u8": str(m3u_root), "playlists": len(pls), "folders": len(folders), "tracks": len(tracks)}
