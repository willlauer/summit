"""Garmin watch: syncs the selected playlists (Summit's and rekordbox's, see library.py).

Most music Garmins (Forerunner, Fenix, Venu…) talk MTP only; a few older
ones mount as a USB drive with a GARMIN/ folder. Both are handled behind the
same small filesystem interface.

Garmin's MTP stack remembers every filename that has ever existed on the
watch: re-sending a deleted name reports success and is silently dropped.
So tracks go over as never-reused numbered files (Music/A0001.mp3, …), and
the watch reads titles from the ID3 tags. The number counter and the
track -> file mapping live in a local index per watch.

Everything that isn't MP3 is transcoded with ffmpeg (ALAC/AIFF/WAV are not
reliably playable on the watch, and the storage is small).
"""

import hashlib
import os
import plistlib
import re
import shutil
import subprocess
import tempfile
import unicodedata

from . import mtp
from .common import APP_DIR, CACHE_DIR, human, load_config, read_json, save_config, write_json

GARMIN_VENDOR_ID = 0x091E
MUSIC = "Music"
AUDIO_EXTS = {".mp3", ".m4a", ".aac", ".wav"}
PLAYLIST_EXTS = (".m3u", ".m3u8")
MTP_BATCH = 50  # reopen the session every N sends; long sessions drop on some watches


# ─── Detection ─────────────────────────────────────────────────────────────

def usb_garmin():
    """Product name of a Garmin on USB, or None. Uses ioreg, so it never opens
    an MTP session (which would fight with a running sync)."""
    try:
        out = subprocess.run(["ioreg", "-a", "-p", "IOUSB", "-l", "-w0"],
                             capture_output=True, timeout=10).stdout
        tree = plistlib.loads(out)
    except Exception:
        return None

    def walk(node):
        if isinstance(node, dict):
            if node.get("idVendor") == GARMIN_VENDOR_ID:
                return node.get("USB Product Name") or node.get("kUSBProductString") or "Garmin"
            for child in node.get("IORegistryEntryChildren", []):
                hit = walk(child)
                if hit:
                    return hit
        return None

    return walk(tree)


def garmin_volume():
    """Mount point of a mass-storage Garmin (a volume with GARMIN/ at its root)."""
    try:
        names = os.listdir("/Volumes")
    except OSError:
        return None
    for n in names:
        p = os.path.join("/Volumes", n)
        if os.path.ismount(p) and os.path.isdir(os.path.join(p, "GARMIN")):
            return p
    return None


def status():
    vol = garmin_volume()
    usb = usb_garmin()
    out = {"id": "garmin", "name": "Garmin", "kind": "Watch", "connected": bool(vol or usb)}
    if vol:
        du = shutil.disk_usage(vol)
        out.update(mode="volume", detail=f"USB drive at {vol}", free=du.free, capacity=du.total)
    elif usb:
        out.update(mode="mtp", name=usb, detail="Connected over MTP")
        if not mtp.available():
            out["warning"] = "libmtp missing — run: brew install libmtp"
    else:
        out["detail"] = "Plug the watch in with its USB cable"
    return out


# ─── Device filesystem (MTP or mounted volume) ─────────────────────────────

class MtpFS:
    def __init__(self):
        self.dev = None
        self.open()

    def open(self):
        self.dev = mtp.Device()
        info = self.dev.info()
        self.key = "mtp-" + (info["serial"] or info["model"] or "garmin")
        self.model = info["name"] or info["model"] or "Garmin"
        self.storage = self.dev.primary_storage()
        self.music_id = self.dev.find_folder(self.storage["id"], MUSIC)

    def reopen(self):
        self.close()
        self.open()

    def close(self):
        if self.dev:
            self.dev.close()
            self.dev = None

    def space(self):
        st = self.dev.primary_storage()
        return st["free"], st["capacity"]

    def list_music(self):
        """Files directly in Music/ — where MusicSync's own tracks and playlists live."""
        if self.music_id is None:
            return {}
        return {i["name"]: i for i in self.dev.list(self.storage["id"], self.music_id) if not i["folder"]}

    def walk_music(self, progress=None):
        """Everything under Music/, depth first: (files, folders), each item
        carrying "path" relative to Music/. Folders come deepest-first so they
        can be deleted in order. Slow over MTP (~30 ms per object)."""
        files, folders = [], []
        if self.music_id is None:
            return files, folders
        sid = self.storage["id"]

        def walk(pid, prefix):
            for i in self.dev.list(sid, pid):
                i["path"] = prefix + i["name"]
                if i["folder"]:
                    walk(i["id"], i["path"] + "/")
                    folders.append(i)
                else:
                    files.append(i)
                    if progress and len(files) % 25 == 0:
                        progress(len(files))
        walk(self.music_id, "")
        return files, folders

    def send(self, local, name):
        if self.music_id is None:
            self.music_id = self.dev.ensure_folder(self.storage["id"], MUSIC)
        self.dev.send(local, name, self.storage["id"], self.music_id)

    def delete(self, item):
        self.dev.delete(item["id"])

    def read_text(self, item):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "x")
            self.dev.download(item["id"], p)
            with open(p, encoding="utf-8", errors="replace") as fh:
                return fh.read()


class VolumeFS:
    def __init__(self, root):
        self.root = os.path.realpath(root)
        self.music = os.path.join(self.root, MUSIC)
        self.key = "vol-" + os.path.basename(self.root)
        self.model = f"Garmin ({os.path.basename(self.root)})"
        os.makedirs(self.music, exist_ok=True)

    def reopen(self):
        pass

    def close(self):
        pass

    def space(self):
        du = shutil.disk_usage(self.root)
        return du.free, du.total

    def _guard(self, path):
        real = os.path.realpath(path)
        if not real.startswith(self.music + os.sep):
            raise RuntimeError(f"refusing to touch {real}: not inside {self.music}")
        return path

    def _item(self, path, folder=False):
        return {"id": path, "name": os.path.basename(path), "path": os.path.relpath(path, self.music),
                "size": 0 if folder else os.path.getsize(path), "folder": folder}

    def list_music(self):
        out = {}
        for fn in os.listdir(self.music):
            p = os.path.join(self.music, fn)
            if os.path.isfile(p) and not fn.startswith("."):
                out[fn] = self._item(p)
        return out

    def walk_music(self, progress=None):
        files, folders = [], []
        for dirpath, dirnames, filenames in os.walk(self.music, topdown=False):
            files += [self._item(os.path.join(dirpath, f)) for f in filenames if not f.startswith(".")]
            if dirpath != self.music:
                folders.append(self._item(dirpath, folder=True))
        return files, folders

    def send(self, local, name):
        dst = self._guard(os.path.join(self.music, name))
        shutil.copyfile(local, dst + ".part")
        os.replace(dst + ".part", dst)

    def delete(self, item):
        path = self._guard(item["id"])
        os.rmdir(path) if item["folder"] else os.unlink(path)

    def read_text(self, item):
        with open(item["id"], encoding="utf-8", errors="replace") as fh:
            return fh.read()


def open_fs():
    vol = garmin_volume()
    if vol:
        return VolumeFS(vol)
    if not usb_garmin():
        raise RuntimeError("No Garmin connected.")
    return MtpFS()


# ─── Local index ───────────────────────────────────────────────────────────

def index_path(key):
    return os.path.join(APP_DIR, "garmin", re.sub(r"[^A-Za-z0-9._-]", "_", key) + ".json")


def load_index(key):
    idx = read_json(index_path(key), {})
    idx.setdefault("next", 1)          # never decremented, never reset
    idx.setdefault("tracks", {})       # library rel -> {file, src_size, src_mtime}
    idx.setdefault("playlists", {})    # playlist name -> {file, hash}
    idx.setdefault("pl_versions", {})  # playlist name -> last version suffix used
    return idx


def save_index(key, idx):
    write_json(index_path(key), idx)


# ─── Helpers ───────────────────────────────────────────────────────────────

def ascii_safe(s):
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()
    return re.sub(r'[\\/:*?"<>|]', "_", s).strip(" .") or "Playlist"


def is_audio(name):
    return os.path.splitext(name)[1].lower() in AUDIO_EXTS


def is_playlist(name):
    return name.lower().endswith(PLAYLIST_EXTS)


def bitrate_bps(cfg):
    m = re.match(r"(\d+)k", cfg["garmin_bitrate"])
    return int(m.group(1)) * 1000 if m else 320000


def estimate_size(rec, cfg):
    if rec["path"].lower().endswith(".mp3"):
        return rec["size"]
    try:
        secs = float(rec["secs"])
    except (TypeError, ValueError):
        secs = 0
    return int(secs * bitrate_bps(cfg) / 8) + 64 * 1024


def ffmpeg_bin():
    return shutil.which("ffmpeg") or next(
        (p for p in ("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg") if os.path.exists(p)), None)


def prepare(rec, cfg):
    """Local path of an MP3 to send: the original if already MP3, else a cached transcode."""
    src = rec["path"]
    if src.lower().endswith(".mp3"):
        return src
    st = os.stat(src)
    key = hashlib.sha1(f"{src}|{st.st_size}|{int(st.st_mtime)}|{cfg['garmin_bitrate']}".encode()).hexdigest()
    out = os.path.join(CACHE_DIR, "mp3", key + ".mp3")
    if os.path.exists(out):
        return out
    ff = ffmpeg_bin()
    if not ff:
        raise RuntimeError("ffmpeg is needed to convert non-MP3 tracks: brew install ffmpeg")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    tmp = out + ".part.mp3"
    r = subprocess.run([ff, "-nostdin", "-v", "error", "-y", "-i", src, "-map", "0:a:0",
                        "-map_metadata", "0", "-c:a", "libmp3lame", "-b:a", cfg["garmin_bitrate"],
                        "-id3v2_version", "3", tmp], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"ffmpeg failed on {os.path.basename(src)}: {r.stderr.strip()[-300:]}")
    os.replace(tmp, out)
    return out


def render_playlist(entries, cfg):
    """entries: [(rec, device_filename)].

    Default format copies what Garmin Express writes, read off a Forerunner 965:
    no header, one absolute upper-case path per line, CRLF:
        0:/MUSIC/ARTIST/TRACK.MP3
    """
    fmt = cfg["garmin_playlist_line"]
    lines = []
    if fmt != "garmin":
        lines.append("#EXTM3U")
    for rec, fname in entries:
        if fmt == "garmin":
            lines.append(f"0:/{MUSIC}/{fname}".upper())
            continue
        try:
            secs = int(float(rec["secs"]))
        except (TypeError, ValueError):
            secs = 0
        label = f"{rec['artist']} - {rec['name']}" if rec["artist"] else rec["name"]
        lines.append(f"#EXTINF:{secs},{ascii_safe(label)}")
        lines.append(fmt.format(file=fname))
    return "\r\n".join(lines) + "\r\n"


# ─── Read / plan / apply ───────────────────────────────────────────────────

def _desired(lib, names):
    """Ordered unique syncable tracks across the chosen playlists."""
    seen, out = set(), []
    for n in names:
        for rec in lib.playlist_tracks(n):
            if rec["rel"] not in seen:
                seen.add(rec["rel"])
                out.append(rec)
    return out


def contents(job=None):
    if job:
        job.step("Opening the watch…")
    fs = open_fs()
    try:
        idx = load_index(fs.key)
        free, cap = fs.space()

        def progress(n):
            if job:
                job.step(f"Reading the watch… {n} files")
        files, _ = fs.walk_music(progress)
        managed = {e["file"] for e in idx["tracks"].values()}
        audio = [f for f in files if is_audio(f["name"])]
        pl_by_file = {v["file"]: k for k, v in idx["playlists"].items()}
        playlists = []
        for f in sorted((f for f in files if is_playlist(f["name"])), key=lambda f: f["path"].casefold()):
            try:
                count = sum(1 for ln in fs.read_text(f).splitlines() if ln.strip() and not ln.startswith("#"))
            except Exception:
                count = None
            playlists.append({"name": os.path.splitext(f["path"])[0], "tracks": count,
                              "library_name": pl_by_file.get(f["path"])})
        return {
            "model": fs.model, "tracks": len(audio), "track_bytes": sum(f["size"] for f in audio),
            "managed": sum(1 for f in audio if f["path"] in managed),
            "unmanaged": sum(1 for f in audio if f["path"] not in managed),
            "playlists": playlists, "free": free, "capacity": cap,
        }
    finally:
        fs.close()


def plan(lib, names, job=None, fs=None):
    cfg = load_config()
    own = fs is None
    if own:
        if job:
            job.step("Opening the watch…")
        fs = open_fs()
    try:
        idx = load_index(fs.key)
        files = fs.list_music()
        free, cap = fs.space()
        want = _desired(lib, names)
        want_rels = {r["rel"] for r in want}

        keep, send, replace = [], [], []
        for rec in want:
            e = idx["tracks"].get(rec["rel"])
            st = os.stat(rec["path"])
            fresh = e and e["src_size"] == st.st_size and e["src_mtime"] == int(st.st_mtime)
            if fresh and e["file"] in files:
                keep.append(rec)
            else:
                send.append(rec)
                if e and e["file"] in files:
                    replace.append(e["file"])
        remove = [e["file"] for rel, e in idx["tracks"].items() if rel not in want_rels and e["file"] in files]
        gone = [rel for rel, e in idx["tracks"].items() if rel not in want_rels and e["file"] not in files]
        del_files = remove + replace
        pl_remove = [v["file"] for k, v in idx["playlists"].items() if k not in names and v["file"] in files]

        add_bytes = sum(estimate_size(r, cfg) for r in send)
        del_bytes = sum(files[f]["size"] for f in del_files)
        net = add_bytes - del_bytes
        managed = {e["file"] for e in idx["tracks"].values()}
        unmanaged = sum(1 for f in files if is_audio(f) and f not in managed)
        need_transcode = sum(1 for r in send if not r["path"].lower().endswith(".mp3"))
        return {
            "fs_key": fs.key, "names": list(names), "send": send, "keep": keep,
            "del_files": del_files, "gone": gone, "pl_remove": pl_remove,
            "summary": {
                "model": fs.model, "playlists_selected": len(names),
                "tracks_selected": len(want), "unchanged": len(keep),
                "add": len(send), "add_bytes": add_bytes, "transcode": need_transcode,
                "delete": len(remove), "replace": len(replace), "delete_bytes": del_bytes,
                "playlists_delete": len(pl_remove), "unmanaged": unmanaged,
                "free": free, "capacity": cap, "net": net, "fits": net <= free,
                "add_sample": [f"{r['artist']} - {r['name']}" if r["artist"] else r["name"] for r in send[:15]],
            },
        }
    finally:
        if own:
            fs.close()


def plan_summary(lib, names, job=None):
    return plan(lib, names, job)["summary"]


def apply(lib, names, job):
    cfg = load_config()
    job.step("Opening the watch…")
    fs = open_fs()
    try:
        p = plan(lib, names, job, fs)
        s = p["summary"]
        if not s["fits"]:
            raise RuntimeError(f"Not enough space on the watch: need {human(s['net'])} more, "
                               f"have {human(s['free'])}")
        idx = load_index(fs.key)
        total = len(p["del_files"]) + len(p["pl_remove"]) + len(p["send"]) + len(names)
        n = errors = 0

        # 1. Deletions first, to free space.
        files = fs.list_music()
        for fname in p["del_files"] + p["pl_remove"]:
            n += 1
            job.step(f"Removing {fname}", n, total)
            if fname in files:
                try:
                    fs.delete(files[fname])
                    job.note(f"- {fname}")
                except Exception as e:
                    errors += 1
                    job.note(f"FAIL delete {fname}: {e}")
        for rel in list(idx["tracks"]):
            if idx["tracks"][rel]["file"] in p["del_files"] or rel in p["gone"]:
                del idx["tracks"][rel]
        for k in [k for k, v in idx["playlists"].items() if v["file"] in p["pl_remove"]]:
            del idx["playlists"][k]
        save_index(fs.key, idx)

        # 2. Tracks. Numbers are burned before sending, so a crash can never reuse one.
        batch = []
        sent = sent_bytes = 0

        def verify(batch):
            nonlocal errors
            if not batch:
                return
            present = fs.list_music()
            for rec, fname, size in batch:
                if fname in present:
                    st = os.stat(rec["path"])
                    idx["tracks"][rec["rel"]] = {"file": fname, "src_size": st.st_size,
                                                 "src_mtime": int(st.st_mtime)}
                else:
                    errors += 1
                    job.note(f"FAIL {fname} ({rec['name']}): the watch accepted it but it is not there")
            save_index(fs.key, idx)
            batch.clear()

        for rec in p["send"]:
            n += 1
            label = f"{rec['artist']} - {rec['name']}" if rec["artist"] else rec["name"]
            try:
                if not rec["path"].lower().endswith(".mp3"):
                    job.step(f"Converting {label}", n, total)
                local = prepare(rec, cfg)
                fname = f"A{idx['next']:04d}.mp3"
                idx["next"] += 1
                save_index(fs.key, idx)
                job.step(f"Sending {label}", n, total)
                fs.send(local, fname)
                size = os.path.getsize(local)
                batch.append((rec, fname, size))
                sent += 1
                sent_bytes += size
                job.note(f"+ {fname}  {label}")
            except mtp.MTPError as e:
                errors += 1
                job.note(f"FAIL {label}: {e}")
            except RuntimeError as e:
                errors += 1
                job.note(f"FAIL {label}: {e}")
            if len(batch) >= MTP_BATCH:
                verify(batch)
                fs.reopen()
        verify(batch)

        # 3. Playlists, pointing at whatever numbered files the tracks have.
        files = fs.list_music()
        with tempfile.TemporaryDirectory() as tmpd:
            for name in names:
                n += 1
                job.step(f"Writing playlist {name}", n, total)
                entries = [(r, idx["tracks"][r["rel"]]["file"]) for r in lib.playlist_tracks(name)
                           if r["rel"] in idx["tracks"]]
                text = render_playlist(entries, cfg)
                digest = hashlib.sha1(text.encode()).hexdigest()
                cur = idx["playlists"].get(name)
                if cur and cur["hash"] == digest and cur["file"] in files:
                    continue
                base = ascii_safe(name)
                local = os.path.join(tmpd, "p.m3u8")
                with open(local, "w", encoding="utf-8", newline="") as fh:
                    fh.write(text)
                if cur and cur["file"] in files:
                    try:
                        fs.delete(files[cur["file"]])
                    except Exception as e:
                        job.note(f"FAIL removing old {cur['file']}: {e}")
                # Same name first; if the watch silently drops it (remembered
                # filename), fall back to a versioned name.
                ver = idx["pl_versions"].get(name, 1)
                for attempt in range(2):
                    fname = f"{base}.m3u8" if ver <= 1 else f"{base} v{ver}.m3u8"
                    try:
                        fs.send(local, fname)
                    except Exception as e:
                        job.note(f"FAIL playlist {fname}: {e}")
                    files = fs.list_music()
                    if fname in files:
                        idx["playlists"][name] = {"file": fname, "hash": digest}
                        idx["pl_versions"][name] = ver
                        job.note(f"♪ {fname} ({len(entries)} tracks)")
                        break
                    ver += 1
                    idx["pl_versions"][name] = ver
                else:
                    errors += 1
                    job.note(f"FAIL playlist {name}: the watch did not keep it")
                save_index(fs.key, idx)

        free, _ = fs.space()
        save_config({"garmin_playlists": list(names)})
        return {"sent": sent, "sent_bytes": sent_bytes, "deleted": len(p["del_files"]),
                "playlists": len(names), "errors": errors, "free": free}
    finally:
        fs.close()


def wipe(job):
    """Delete every track and playlist under the watch's Music/ folder,
    including subfolders (Garmin Express files music as Artist/Album/)."""
    job.step("Opening the watch…")
    fs = open_fs()
    try:
        files, folders = fs.walk_music(lambda n: job.step(f"Reading the watch… {n} files"))
        targets = [f for f in files if is_audio(f["name"]) or is_playlist(f["name"])]
        total = len(targets) + len(folders)
        for i, f in enumerate(targets, 1):
            job.step(f"Deleting {f['path']}", i, total)
            try:
                fs.delete(f)
            except Exception as e:
                job.note(f"FAIL {f['path']}: {e}")
            if i % MTP_BATCH == 0:
                fs.reopen()
        # Then the emptied Artist/Album folders, deepest first. A folder that
        # still holds a non-music file is left in place.
        for j, d in enumerate(folders, len(targets) + 1):
            job.step(f"Removing folder {d['path']}", j, total)
            try:
                fs.delete(d)
            except Exception:
                pass
        idx = load_index(fs.key)
        idx["tracks"] = {}
        idx["playlists"] = {}
        save_index(fs.key, idx)  # keeps "next" and "pl_versions": names stay burned
        free, _ = fs.space()
        return {"deleted": len(targets), "free": free}
    finally:
        fs.close()
