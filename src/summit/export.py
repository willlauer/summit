"""Step 7: playlists out. rekordbox XML (a `summit/` folder of playlists) plus one .m3u8 per playlist.

In rekordbox: Preferences → Advanced → rekordbox xml → point "Imported Library" at data/exports/summit.xml,
then drag playlists from the "rekordbox xml" tree into your collection. Tracks match by file path.
"""

import re
from urllib.parse import quote
from xml.sax.saxutils import quoteattr

from .config import EXPORTS

KIND = {"mp3": "MP3 File", "m4a": "M4A File", "aiff": "AIFF File", "aif": "AIFF File", "wav": "WAV File", "flac": "FLAC File"}


def location(path: str) -> str:
    return "file://localhost" + quote(path, safe="/")


def export_all(con) -> dict:
    pls = [dict(r) for r in con.execute("SELECT id, name FROM playlists ORDER BY name")]
    if con.execute("SELECT 1 FROM likes LIMIT 1").fetchone():
        pls.insert(0, {"id": None, "name": "Liked"})
    EXPORTS.mkdir(parents=True, exist_ok=True)
    (EXPORTS / "m3u8").mkdir(exist_ok=True)
    tracks, lists = {}, []
    for pl in pls:
        rows = con.execute(
            "SELECT t.* FROM playlist_tracks p JOIN tracks t ON t.id=p.track_id WHERE p.playlist_id=? ORDER BY p.position", (pl["id"],)
        ).fetchall() if pl["id"] is not None else con.execute(
            "SELECT t.* FROM likes l JOIN tracks t ON t.id=l.track_id ORDER BY l.created_at DESC").fetchall()
        lists.append((pl["name"], rows))
        for r in rows:
            tracks.setdefault(r["id"], (len(tracks) + 1, r))
        m3u = ["#EXTM3U"] + [f"#EXTINF:{int(r['duration'] or 0)},{r['artist']} - {r['title']}\n{r['path']}" for r in rows]
        safe = re.sub(r"[^\w\- ]+", "_", pl["name"]).strip() or f"playlist-{pl['id']}"
        (EXPORTS / "m3u8" / f"{safe}.m3u8").write_text("\n".join(m3u) + "\n", encoding="utf-8")

    x = ['<?xml version="1.0" encoding="UTF-8"?>', '<DJ_PLAYLISTS Version="1.0.0">', '  <PRODUCT Name="Summit" Version="0.1" Company=""/>',
         f'  <COLLECTION Entries="{len(tracks)}">']
    for tid, r in tracks.values():
        x.append(f'    <TRACK TrackID="{tid}" Name={quoteattr(r["title"] or "")} Artist={quoteattr(r["artist"] or "")} '
                 f'Album={quoteattr(r["album"] or "")} Kind="{KIND.get(r["format"], "")}" TotalTime="{int(r["duration"] or 0)}" '
                 f'Location={quoteattr(location(r["path"]))}/>')
    x += ["  </COLLECTION>", "  <PLAYLISTS>", '    <NODE Type="0" Name="ROOT" Count="1">', f'      <NODE Type="0" Name="summit" Count="{len(lists)}">']
    for name, rows in lists:
        x.append(f'        <NODE Name={quoteattr(name)} Type="1" KeyType="0" Entries="{len(rows)}">')
        x += [f'          <TRACK Key="{tracks[r["id"]][0]}"/>' for r in rows]
        x.append("        </NODE>")
    x += ["      </NODE>", "    </NODE>", "  </PLAYLISTS>", "</DJ_PLAYLISTS>"]
    (EXPORTS / "summit.xml").write_text("\n".join(x) + "\n", encoding="utf-8")
    return {"xml": str(EXPORTS / "summit.xml"), "m3u8": str(EXPORTS / "m3u8"), "playlists": len(lists), "tracks": len(tracks)}
