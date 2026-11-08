#!/usr/bin/env python3
"""
sync-rekordbox-to-sd.py — mirror a rekordbox library and its playlists onto an
Android MicroSD card.

    # in rekordbox: File > Export Collection in xml format  ->  ~/Documents/rekordbox.xml
    ./sync-rekordbox-to-sd.py            # dry run: says what it would do
    ./sync-rekordbox-to-sd.py --apply    # actually does it

Three coordinate systems, one relative path:

    Mac      ~/Music/Music/Media.localized/Music/<rel>
    Card     /Volumes/Untitled/Music/<card_name(rel)>
    Android  /storage/external_sd/Music/<card_name(rel)>

card_name() swaps punctuation the FiiO player can't resolve (e.g. the '·' in
'LOVE·LY') for ASCII, so the card side may differ from the Mac spelling.

The card becomes an exact mirror of the rekordbox collection: tracks in the
collection are copied over, tracks no longer in it are deleted from the card,
and every playlist is regenerated as an .m3u8 in Documents/.

NOTHING OUTSIDE THE CARD IS EVER WRITTEN OR DELETED. Every mutating call routes
through guard(), which resolves the target and refuses anything not under the
card root. The Mac library is opened read-only.

Standard library only — no pip install. Safe to re-run: an interrupted sync
resumes rather than starting over.
"""

import argparse
import os
import shutil
import sys
import unicodedata
import urllib.parse
import xml.etree.ElementTree as ET

AUDIO_EXTS = {".mp3", ".m4a", ".wav", ".aiff", ".aif", ".flac", ".ogg", ".opus", ".aac", ".alac"}

# Left alone entirely: a rekordbox CDJ device export, a separate workflow from
# the m3u8 one, plus the volume's own bookkeeping.
SKIP_TOPLEVEL = {"PIONEER", "Contents", ".Spotlight-V100", ".fseventsd", ".TemporaryItems", "LOST.DIR"}

# exFAT rejects these outright, and trailing dots/spaces get silently eaten.
ILLEGAL_CHARS = set(':\\"*?<>|')

# The FiiO player reports "file not found" for playlist paths containing
# non-ASCII punctuation — 'LOVE·LY/02 HOPE.aiff' was on the card and
# byte-identical in the .m3u8, and still failed. Accented letters (é, ü, Ø) are
# fine, so only non-letters are rewritten: common typography to its ASCII
# look-alike, anything else to '_' (the stand-in Music.app itself uses).
ASCII_EQUIV = {
    "·": ".", "•": ".", "°": "o", "×": "x", "…": "...", "♾": "oo",
    "‘": "'", "’": "'", "‚": "'", "“": "'", "”": "'", "„": "'",
    "‐": "-", "‑": "-", "–": "-", "—": "-", "−": "-",
}

if sys.stdout.isatty():
    R, G, Y, B, N = "\033[31m", "\033[32m", "\033[33m", "\033[1m", "\033[0m"
else:
    R = G = Y = B = N = ""


def hdr(msg):
    print(f"\n{B}{msg}{N}")


def ok(msg):
    print(f"{G}OK{N}   {msg}")


def warn(msg):
    print(f"{Y}WARN{N} {msg}")


def err(msg):
    print(f"{R}FAIL{N} {msg}")


def nfc(s):
    return unicodedata.normalize("NFC", s)


def human(n):
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024:
            return f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} PB"


# ─── The safety rail ───────────────────────────────────────────────────────
# Built first and used by every mutating operation below. There is deliberately
# no way to write or unlink in this script that does not pass through here.

class Card:
    def __init__(self, mount):
        self.root = os.path.realpath(os.path.expanduser(mount))
        if self.root in ("/", ""):
            sys.exit(f"refusing to treat {self.root!r} as the card root")
        if not os.path.isdir(self.root):
            sys.exit(f"card not mounted: {self.root}")
        if not os.path.ismount(self.root):
            sys.exit(f"not a mount point: {self.root}\n"
                     f"     Pass the volume itself, e.g. --card /Volumes/Untitled")
        if not os.access(self.root, os.W_OK):
            sys.exit(f"card is not writable: {self.root}\n"
                     f"     An NTFS volume formatted on Windows mounts read-only on macOS;\n"
                     f"     reformat as exFAT.")

    def guard(self, path):
        """Return path, or abort if it is not inside the card."""
        real = os.path.realpath(path)
        if real != self.root and not real.startswith(self.root + os.sep):
            raise RuntimeError(f"REFUSING to touch a path outside the card:\n  {real}\n  card: {self.root}")
        return path

    def unlink(self, path):
        os.unlink(self.guard(path))

    def rmdir(self, path):
        os.rmdir(self.guard(path))

    def copy_in(self, src, dst):
        """Copy src (anywhere) to dst (must be on the card), preserving mtime.

        copyfile + utime rather than copy2: copy2 carries xattrs across, and
        writing xattrs to exFAT is exactly what makes the kernel spawn ._*
        sidecar files — the 613 MB of junk this script also cleans up.
        """
        self.guard(dst)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        tmp = dst + ".part"
        self.guard(tmp)
        try:
            shutil.copyfile(src, tmp)
            st = os.stat(src)
            os.utime(tmp, (st.st_atime, st.st_mtime))
            os.replace(tmp, dst)
        except BaseException:
            if os.path.exists(tmp):
                try:
                    self.unlink(tmp)
                except OSError:
                    pass
            raise

    def rename(self, src, dst):
        """Move a file within the card, creating dst's directory as needed."""
        self.guard(src)
        self.guard(dst)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        os.rename(src, dst)

    def restamp(self, src, dst):
        """Copy src's mtime onto dst, so a same-size file stops looking changed."""
        st = os.stat(src)
        os.utime(self.guard(dst), (st.st_atime, st.st_mtime))

    def write_text(self, path, text):
        self.guard(path)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)


# ─── Reading the rekordbox export ──────────────────────────────────────────

def library_index(library_root):
    """casefolded rel -> the real on-disk rel, for every audio file in the library.

    rekordbox records whatever spelling a file had when it was imported, so the
    same track can appear as both 'SOFI TUKKER/…' and 'Sofi Tukker/…'. macOS is
    case-insensitive, so both open the same file and both look valid — but only
    one spelling is what the directory actually holds. Canonicalizing through
    this index collapses those duplicates and makes the card comparison exact.
    """
    index = {}
    for dirpath, dirnames, filenames in os.walk(library_root):
        for fn in filenames:
            if fn.startswith("._") or os.path.splitext(fn)[1].lower() not in AUDIO_EXTS:
                continue
            rel = nfc(os.path.relpath(os.path.join(dirpath, fn), library_root))
            index[rel.casefold()] = rel
    return index


def parse_collection(xml_path, library_root, index):
    """TrackID -> record, partitioned by whether we can actually sync it."""
    root = ET.parse(xml_path).getroot()
    collection = root.find("COLLECTION")
    if collection is None:
        sys.exit(f"{xml_path} has no <COLLECTION> — is this a rekordbox XML export?")

    tracks, streaming, outside, missing = {}, [], [], []
    prefix = "file://localhost"
    for t in collection.findall("TRACK"):
        loc = t.get("Location", "")
        rec = {
            "artist": t.get("Artist", ""),
            "name": t.get("Name", ""),
            "secs": t.get("TotalTime", "0"),
            "path": None,
            "rel": None,
        }
        tracks[t.get("TrackID")] = rec

        if not loc.startswith(prefix):
            rec["why"] = "streaming"
            streaming.append(loc or "(no location)")
            continue
        path = nfc(urllib.parse.unquote(loc[len(prefix):]))
        if not path.startswith(library_root):
            rec["why"] = "outside library root"
            outside.append(path)
            continue
        real = index.get(path[len(library_root):].casefold())
        if real is None:
            rec["why"] = "missing on disk"
            missing.append(path[len(library_root):])
            continue
        rec["rel"] = real
        rec["path"] = library_root + real

    return root, tracks, streaming, outside, missing


def flatten(parts):
    """['Gfish-sarah-2026', 'GS - Closers / Anthems'] -> 'Gfish-sarah-2026 - GS - Closers _ Anthems'

    Folder path joined with ' - '; '/' inside a name becomes '_', since it
    cannot survive in a filename. Matches the naming already on the card.
    """
    return " - ".join(p.replace("/", "_") for p in parts)


def parse_playlists(root):
    """[(flattened_name, [TrackID, ...]), ...] in rekordbox's own order."""
    container = root.find("PLAYLISTS")
    if container is None:
        return []
    top = container.find("NODE")
    if top is None:
        return []

    out = []

    def walk(node, parts):
        for child in node.findall("NODE"):
            name = child.get("Name", "")
            if child.get("Type") == "1":
                out.append((flatten(parts + [name]), [x.get("Key") for x in child.findall("TRACK")]))
            else:
                walk(child, parts + [name])

    # Only the outermost node is the synthetic "ROOT" wrapper; descend from it
    # with an empty path so top-level playlists get no prefix. Dropping the
    # first component unconditionally would blank out every top-level name.
    walk(top, [])

    seen = {}
    for name, _ in out:
        seen.setdefault(name, 0)
        seen[name] += 1
    dupes = sorted(n for n, c in seen.items() if c > 1)
    if dupes:
        err("two or more playlists flatten to the same filename:")
        for d in dupes:
            print(f"       {d}.m3u8")
        sys.exit("rename one of them in rekordbox; writing both would lose a playlist")
    return out


def card_name(rel):
    """The FiiO-safe spelling of a library-relative path. Deterministic, so the
    card<->Mac comparison stays exact: both sides go through this."""
    out = []
    for ch in rel:
        if ch.isascii():
            out.append(ch)
        elif ch in ASCII_EQUIV:
            out.append(ASCII_EQUIV[ch])
        else:
            cat = unicodedata.category(ch)
            compat = unicodedata.normalize("NFKC", ch)
            if cat == "Cf" or "\ufe00" <= ch <= "\ufe0f":
                pass  # invisible: word joiner, zero-width space, emoji selector
            elif cat[0] in "LNM":
                out.append(ch)
            elif compat.isascii():
                out.append(compat)  # fullwidth （, one-dot leader ․, no-break space
            elif cat[0] == "Z":
                out.append(" ")
            else:
                out.append("_")
    return nfc("".join(out))


def check_exfat_safe(rels):
    """exFAT-hostile names would have to be mangled, and mangling breaks the
    card<->Mac comparison (every file would look new). Refuse instead."""
    bad = []
    for rel in rels:
        if ILLEGAL_CHARS & set(rel) or any(seg.endswith((".", " ")) for seg in rel.split("/")):
            bad.append(rel)
    return sorted(bad)


# ─── Walking the card ──────────────────────────────────────────────────────

def card_audio(card, music_dir):
    """rel -> absolute path, for every audio file in the card's Music/."""
    found = {}
    if not os.path.isdir(music_dir):
        return found
    for dirpath, dirnames, filenames in os.walk(music_dir):
        dirnames[:] = [d for d in dirnames if d not in SKIP_TOPLEVEL]
        for fn in filenames:
            if fn.startswith("._") or os.path.splitext(fn)[1].lower() not in AUDIO_EXTS:
                continue
            full = os.path.join(dirpath, fn)
            found[nfc(os.path.relpath(full, music_dir))] = full
    return found


def sidecars(card):
    """Every ._* / .DS_Store on the card, skipping volumes we can't read."""
    out = []
    for dirpath, dirnames, filenames in os.walk(card.root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_TOPLEVEL]
        for fn in filenames:
            if fn.startswith("._") or fn == ".DS_Store":
                out.append(os.path.join(dirpath, fn))
    return out


def prune_empty(card, top):
    """Remove directories left empty by deletions. Bottom-up so nesting collapses."""
    removed = 0
    for dirpath, dirnames, filenames in os.walk(top, topdown=False):
        if dirpath == top:
            continue
        try:
            if not os.listdir(dirpath):
                card.rmdir(dirpath)
                removed += 1
        except OSError:
            pass
    return removed


# ─── Playlist rendering ────────────────────────────────────────────────────

def render(entries, android_root):
    """#EXTM3U + one #EXTINF/path pair per track. LF, UTF-8, no BOM.

    The #EXTINF label comes from rekordbox's Artist/Name fields, not from the
    file path — they disagree often enough to matter (one track is tagged
    'Hellmuth - Stockholm Syndrome' but filed under Markus Schulz/).
    """
    lines = ["#EXTM3U"]
    for rec in entries:
        secs = rec["secs"]
        try:
            secs = str(int(float(secs)))
        except (TypeError, ValueError):
            secs = "0"
        label = f"{rec['artist']} - {rec['name']}" if rec["artist"] else rec["name"]
        lines.append(f"#EXTINF:{secs},{label}")
        lines.append(f"{android_root}/{rec['card']}")
    return "\n".join(lines) + "\n"


# ─── main ──────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(
        description="Mirror a rekordbox library and its playlists onto a MicroSD card.",
        epilog="Dry run by default. Pass --apply to actually write. Only ever writes to --card.",
    )
    ap.add_argument("--xml", default="~/Documents/rekordbox.xml",
                    help="rekordbox XML export (File > Export Collection in xml format)")
    ap.add_argument("--card", default="/Volumes/Untitled", help="card mount point")
    ap.add_argument("--library-root", default="~/Music/Music/Media.localized/Music",
                    help="root of the music library on this Mac")
    ap.add_argument("--android-root", default="/storage/external_sd/Music",
                    help="path prefix the Android device sees")
    ap.add_argument("--apply", action="store_true", help="make the changes (default: dry run)")
    args = ap.parse_args()

    xml_path = os.path.expanduser(args.xml)
    if not os.path.isfile(xml_path):
        sys.exit(f"no such XML export: {xml_path}\n"
                 f"     In rekordbox: File > Export Collection in xml format")
    library_root = os.path.realpath(os.path.expanduser(args.library_root)).rstrip("/") + "/"
    if not os.path.isdir(library_root):
        sys.exit(f"library root does not exist: {library_root}")
    android_root = args.android_root.rstrip("/")

    card = Card(args.card)
    music_dir = os.path.join(card.root, "Music")
    docs_dir = os.path.join(card.root, "Documents")

    hdr("Sources")
    print(f"  xml     {xml_path} ({human(os.path.getsize(xml_path))})")
    print(f"  library {library_root}")
    print(f"  card    {card.root}")
    print(f"  android {android_root}/")
    if not args.apply:
        print(f"\n  {B}DRY RUN{N} — nothing will be written. Re-run with --apply.")

    # ── read the export ────────────────────────────────────────────────────
    hdr("Reading the rekordbox export")
    index = library_index(library_root)
    root, tracks, streaming, outside, missing = parse_collection(xml_path, library_root, index)
    playlists = parse_playlists(root)
    syncable = {tid: r for tid, r in tracks.items() if r["rel"]}
    print(f"  {len(tracks)} collection entries, {len(playlists)} playlists")
    ok(f"{len(syncable)} tracks are local files under the library root")
    for label, items in (("streaming (no local file)", streaming),
                         ("outside the library root", outside),
                         ("in rekordbox but missing on disk", missing)):
        if items:
            warn(f"{len(items)} {label} — cannot sync")
            for x in items[:3]:
                print(f"       {x}")
            if len(items) > 3:
                print(f"       … and {len(items) - 3} more")

    # From here on, everything on the card side is keyed by card_name(rel).
    want, origin = {}, {}
    for r in syncable.values():
        r["card"] = card_name(r["rel"])
        prev = origin.setdefault(r["card"].casefold(), r["rel"])
        if prev != r["rel"]:
            err(f"two tracks map to the same card path {r['card']!r}:")
            print(f"       {prev}\n       {r['rel']}")
            sys.exit("rename one of them in Music.app")
        want[r["card"]] = r["path"]
    orphan_rels = {card_name(nfc(m)) for m in missing}

    bad = check_exfat_safe(want)
    if bad:
        err(f"{len(bad)} paths contain characters exFAT cannot store:")
        for x in bad[:20]:
            print(f"       {x}")
        sys.exit("rename these in Music.app; renaming them here would make every file look new")
    ok("no exFAT-illegal characters in any path")

    # ── what changes ───────────────────────────────────────────────────────
    hdr("Comparing against the card")
    have = card_audio(card, music_dir)
    # exFAT is case-insensitive: Music/Meduza and Music/MEDUZA are one directory
    # (same inode). The Mac library treats them as two. So every card lookup has
    # to be case-folded — comparing literal strings makes one spelling look like
    # an addition and the other like a deletion, and since deletes run after
    # copies, the delete removes the file the copy just wrote.
    have_ci = {r.casefold(): r for r in have}

    to_add, to_touch, unchanged = [], [], 0
    for rel, src in sorted(want.items()):
        card_rel = have_ci.get(rel.casefold())
        dst = have[card_rel] if card_rel else None
        if dst is None:
            to_add.append(rel)
            continue
        # Size only. The card was last filled from a different machine whose
        # copies carry different tags/artwork, so mtimes disagree across the
        # board and are useless as a signal. Same size means same file for our
        # purposes; when the mtime differs we just restamp it so the next run
        # is a genuine no-op.
        s, d = os.stat(src), os.stat(dst)
        if s.st_size != d.st_size:
            to_add.append(rel)
        else:
            unchanged += 1
            if int(s.st_mtime) != int(d.st_mtime):
                to_touch.append((src, dst))
    # A track still in rekordbox whose Mac file has gone missing: the card copy
    # may be the last one left, so keep it rather than mirroring the loss.
    orphan_ci = {r.casefold() for r in orphan_rels}
    want_ci = {r.casefold() for r in want}
    keep = {r for r in have if r.casefold() in orphan_ci}
    to_delete = sorted(r for r in have if r.casefold() not in want_ci and r not in keep)

    # A card file written under its pre-card_name() spelling (e.g. LOVE·LY/) is
    # the same file under an old name: rename it in place rather than copying
    # it again and deleting the original.
    add_ci = {r.casefold(): r for r in to_add}
    to_rename = []
    for old in to_delete:
        new = add_ci.get(card_name(old).casefold())
        if new and os.path.getsize(have[old]) == os.path.getsize(want[new]):
            to_rename.append((old, new))
            del add_ci[new.casefold()]
    renamed_old = {o for o, _ in to_rename}
    renamed_new = {n for _, n in to_rename}
    to_delete = [r for r in to_delete if r not in renamed_old]
    to_add = [r for r in to_add if r not in renamed_new]

    add_bytes = sum(os.path.getsize(want[r]) for r in to_add)
    del_bytes = sum(os.path.getsize(have[r]) for r in to_delete)
    junk = sidecars(card)
    junk_bytes = sum(os.path.getsize(p) for p in junk if os.path.exists(p))
    stale = sorted(f for f in os.listdir(music_dir) if f.lower().endswith((".m3u", ".m3u8"))) \
        if os.path.isdir(music_dir) else []

    new_names = {f"{name}.m3u8" for name, _ in playlists}
    old_names = sorted(f for f in os.listdir(docs_dir)
                       if f.lower().endswith((".m3u", ".m3u8")) and not f.startswith("._")) \
        if os.path.isdir(docs_dir) else []
    pl_delete = sorted(set(old_names) - new_names)

    print(f"  unchanged  {unchanged}" + (f"  ({len(to_touch)} need an mtime restamp)" if to_touch else ""))
    print(f"  to add     {len(to_add)}  ({human(add_bytes)})")
    print(f"  to delete  {len(to_delete)}  ({human(del_bytes)})")
    if to_rename:
        print(f"  to rename  {len(to_rename)}  (already on the card under a FiiO-unsafe name)")
        for old, new in to_rename[:5]:
            print(f"       {old}\n    -> {new}")
        if len(to_rename) > 5:
            print(f"       … and {len(to_rename) - 5} more")
    if keep:
        print(f"  kept       {len(keep)} card-only file(s) whose Mac copy is missing "
              f"but which are still in rekordbox")
    print(f"  playlists  {len(playlists)} to write, {len(pl_delete)} stale to remove from Documents/")
    if stale:
        print(f"  stale      {len(stale)} obsolete playlist file(s) in Music/ to remove")
    if junk:
        print(f"  sidecars   {len(junk)} ._*/.DS_Store to remove ({human(junk_bytes)})")

    # Playlists whose lines will be short because a track isn't syncable.
    incomplete = []
    for name, ids in playlists:
        skipped = sum(1 for i in ids if i not in syncable)
        if skipped:
            incomplete.append((name, skipped, len(ids)))
    if incomplete:
        warn(f"{len(incomplete)} playlist(s) reference tracks that cannot be synced; "
             f"those lines are omitted:")
        for name, sk, tot in sorted(incomplete, key=lambda x: -x[1]):
            print(f"       {name}: {sk}/{tot}")

    free = shutil.disk_usage(card.root).free
    needed = add_bytes - del_bytes - junk_bytes
    print(f"\n  free now   {human(free)}")
    print(f"  net change {human(needed)}")
    if needed > free:
        sys.exit(f"not enough free space: need {human(needed)}, have {human(free)}")

    if not args.apply:
        hdr("Dry run complete")
        print("  Re-run with --apply to make these changes.")
        return 0

    # ── apply ──────────────────────────────────────────────────────────────
    if to_rename:
        hdr("Renaming to FiiO-safe names")
        for old, new in to_rename:
            try:
                card.rename(have[old], os.path.join(music_dir, new))
                print(f"  {old}\n    -> {new}")
            except OSError as e:
                err(f"{old}: {e}")

    hdr("Copying")
    copied = copied_bytes = 0
    for i, rel in enumerate(to_add, 1):
        src = want[rel]
        dst = os.path.join(music_dir, rel)
        print(f"  [{i}/{len(to_add)}] {rel}")
        try:
            card.copy_in(src, dst)
            copied += 1
            copied_bytes += os.path.getsize(dst)
        except OSError as e:
            err(f"{rel}: {e}")
    for src, dst in to_touch:
        try:
            card.restamp(src, dst)
        except OSError as e:
            warn(f"could not restamp {dst}: {e}")
    if to_touch:
        ok(f"restamped {len(to_touch)} unchanged file(s) so re-runs skip them")

    hdr("Deleting tracks no longer in rekordbox")
    deleted = deleted_bytes = 0
    for rel in to_delete:
        path = have[rel]
        try:
            size = os.path.getsize(path)
            card.unlink(path)
            deleted += 1
            deleted_bytes += size
            print(f"  - {rel}")
        except OSError as e:
            err(f"{rel}: {e}")

    hdr("Writing playlists")
    written = 0
    for name, ids in playlists:
        entries = [syncable[i] for i in ids if i in syncable]
        card.write_text(os.path.join(docs_dir, f"{name}.m3u8"), render(entries, android_root))
        written += 1
        print(f"  {len(entries):5d}  {name}.m3u8")
    for fn in pl_delete:
        try:
            card.unlink(os.path.join(docs_dir, fn))
            print(f"  -      {fn}")
        except OSError as e:
            err(f"{fn}: {e}")
    for fn in stale:
        try:
            card.unlink(os.path.join(music_dir, fn))
            print(f"  - (stale, Music/) {fn}")
        except OSError as e:
            err(f"Music/{fn}: {e}")

    hdr("Cleaning up")
    swept = swept_bytes = 0
    # Re-walk rather than reusing the preflight list: copying and deleting churns
    # the card, so that list is stale by now — it both names files that are
    # already gone and misses ones that appeared since.
    for p in sidecars(card):
        try:
            size = os.path.getsize(p)
            card.unlink(p)
            swept += 1
            swept_bytes += size
        except OSError:
            pass
    ok(f"removed {swept} sidecar file(s), {human(swept_bytes)}")
    pruned = prune_empty(card, music_dir)
    ok(f"pruned {pruned} empty director{'y' if pruned == 1 else 'ies'}")

    hdr("Result")
    print(f"  copied    {copied} files, {human(copied_bytes)}")
    print(f"  deleted   {deleted} files, {human(deleted_bytes)}")
    if to_rename:
        print(f"  renamed   {len(to_rename)} files")
    print(f"  unchanged {unchanged} files")
    print(f"  playlists {written} written, {len(pl_delete) + len(stale)} removed")
    print(f"  free      {human(shutil.disk_usage(card.root).free)}")
    print(f"\n  Eject with:  diskutil eject {card.root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
