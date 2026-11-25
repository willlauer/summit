"""The Music app (iTunes) library's playlists, for device sync: read through Apple's iTunesLibrary framework, which is
read-only by design (see CLAUDE.md: the iTunes files are NOT to be touched; this only reads them).

Only playlists you made (regular and smart, with their folder path) count; the built-in ones (Library, Music,
Purchased, Music Videos…) are skipped. Never raises: a problem is reported in the status and contributes nothing.
"""

import time
import urllib.parse

_cache = {"at": 0.0, "val": None}
TTL = 120  # the framework re-reads the whole library each time; don't do that on every page load


def read(force=False):
    """(status, [(path_parts, [file paths in order])]). status: {"error", "playlists", "tracks"}."""
    if not force and _cache["val"] is not None and time.time() - _cache["at"] < TTL:
        return _cache["val"]
    try:
        from iTunesLibrary import ITLibrary
        lib, err = ITLibrary.alloc().initWithAPIVersion_error_("1.1", None)
        if lib is None:
            raise RuntimeError(str(err) if err else "Music library unavailable")
        pls = list(lib.allPlaylists())
        by_id = {int(p.persistentID()): p for p in pls}

        def parts(p):
            out, seen = [str(p.name())], set()
            parent = p.parentID()
            while parent is not None and int(parent) in by_id and int(parent) not in seen:
                seen.add(int(parent))
                q = by_id[int(parent)]
                out.insert(0, str(q.name()))
                parent = q.parentID()
            return out

        out = []
        for p in pls:
            # kind 0 regular, 1 smart (3 folder, 2/4 Genius); distinguishedKind 0 = made by you, not built in
            if p.isMaster() or p.distinguishedKind() != 0 or p.kind() not in (0, 1) or not p.isVisible():
                continue
            paths = []
            for it in p.items():
                loc = it.location()
                if loc is not None and loc.isFileURL():
                    paths.append(urllib.parse.unquote(str(loc.path())))
            out.append((parts(p), paths))
        val = ({"error": None, "playlists": len(out), "tracks": len(lib.allMediaItems())}, out)
    except Exception as e:  # no framework, no permission, no library
        val = ({"error": f"Couldn't read the Music library: {e}", "playlists": 0, "tracks": 0}, [])
    _cache.update(at=time.time(), val=val)
    return val
