"""/api/sync/…: device sync (Garmin watch, FiiO MicroSD card) for the desktop app's Sync mode.

Desktop only: these endpoints answer requests made on this Mac and refuse anything relayed (tailscale serve, any
proxy), so the phone, or anyone else who can reach the server, can't read or wipe a device plugged into it.
"""

import os
import plistlib
import subprocess

from fastapi import APIRouter, Body, Depends, HTTPException, Request

from . import common, fiio, garmin, mtp
from .library import build, resolve_xml

LOCAL = {"127.0.0.1", "::1", "localhost"}
RELAYED = ("x-forwarded-for", "x-forwarded-host", "forwarded", "tailscale-user-login", "tailscale-funnel-request")


def local_only(request: Request) -> None:
    host = request.client.host if request.client else ""
    if host not in LOCAL or any(h in request.headers for h in RELAYED):
        raise HTTPException(403, "Device sync is only available in the Summit app on this Mac.")
    origin = request.headers.get("origin")
    if request.method != "GET" and origin and origin.split("://", 1)[-1].split(":")[0] not in LOCAL:
        raise HTTPException(403, "forbidden")


router = APIRouter(prefix="/api/sync", dependencies=[Depends(local_only)])
source = None  # set by the server: () -> the Summit side of the library (see library.build)
_contents = {}  # device -> its last read, so revisiting a device shows it at once


def library(force=False):
    try:
        return build(source(), force)
    except FileNotFoundError as e:
        raise HTTPException(404, str(e))


def external_volumes():
    """Mounted volumes the user could pick as the FiiO card."""
    out = []
    try:
        names = sorted(os.listdir("/Volumes"))
    except OSError:
        return out
    for n in names:
        p = os.path.join("/Volumes", n)
        if not os.path.ismount(p) or os.path.realpath(p) == "/":
            continue
        try:
            d = plistlib.loads(subprocess.run(["diskutil", "info", "-plist", p], capture_output=True, timeout=5).stdout)
        except Exception:
            d = {}
        if d.get("Internal") and not d.get("RemovableMedia"):
            continue
        if d.get("BusProtocol") == "Disk Image" or d.get("VirtualOrPhysical") == "Virtual":
            continue
        out.append({"path": p, "name": n, "fs": d.get("FilesystemName", ""), "size": d.get("TotalSize")})
    return out


@router.get("/devices")
def devices():
    cfg = common.load_config()
    job = common.current_job()
    busy = job.device if job and job.status == "running" else None
    out = []
    for st in (garmin.status(), fiio.status(cfg)):
        st["busy"] = st["id"] == busy
        out.append(st)
    return {"devices": out}


@router.get("/library")
def library_summary():
    return library().summary()


@router.post("/library/reload")
def library_reload():
    return library(force=True).summary()


@router.get("/config")
def config():
    cfg = common.load_config()
    cfg["xml_resolved"] = resolve_xml(cfg)
    cfg["volumes"] = external_volumes()
    cfg["libmtp"] = mtp.available()
    cfg["ffmpeg"] = bool(garmin.ffmpeg_bin())
    return cfg


@router.post("/config")
def save_config(body: dict = Body(...)):
    return common.save_config(body)


@router.get("/job")
def job():
    j = common.current_job()
    return {"job": j.to_dict() if j else None}


@router.post("/job/cancel")
def cancel():
    j = common.current_job()
    if j and j.status == "running":
        j.cancel_requested = True
    return {"ok": True}


@router.get("/contents/{dev}")
def cached_contents(dev: str):
    return {"contents": _contents.get(dev)}


def _garmin_names(lib, body):
    names = body.get("playlists")
    if names is None:
        names = common.load_config()["garmin_playlists"]
    known = {n for n, _ in lib.playlists}
    missing = [n for n in names if n not in known]
    if missing:
        raise HTTPException(400, f"No longer a playlist: {', '.join(missing)}")
    return names


@router.post("/{action}/{dev}")
def act(action: str, dev: str, body: dict = Body(default={})):
    if dev not in ("garmin", "fiio"):
        raise HTTPException(404, "unknown device")
    name = "Garmin" if dev == "garmin" else "FiiO card"
    try:
        if action == "contents":
            if dev == "garmin":
                j = common.start_isolated_job("contents", dev, f"Reading {name}", "summit.sync.garmin", "contents")
            else:
                def run(j):
                    c = fiio.contents(j)
                    _contents[dev] = c
                    return c
                j = common.start_job("contents", dev, f"Reading {name}", run)
        elif action == "plan":
            lib = library()
            if dev == "garmin":
                j = common.start_isolated_job("plan", dev, f"Previewing {name} sync", "summit.sync.garmin", "plan_summary",
                                              (lib, _garmin_names(lib, body)))
            else:
                j = common.start_job("plan", dev, f"Previewing {name} sync", lambda j: fiio.plan(lib, j)["summary"])
        elif action == "sync":
            lib = library()
            if dev == "garmin":
                names = _garmin_names(lib, body)
                common.save_config({"garmin_playlists": names})
                j = common.start_isolated_job("sync", dev, f"Syncing {name}", "summit.sync.garmin", "apply", (lib, names))
            else:
                j = common.start_job("sync", dev, f"Syncing {name}", lambda j: fiio.apply(lib, j))
        elif action == "wipe":
            if body.get("confirm") != "DELETE":
                raise HTTPException(400, "confirmation missing")
            title = f"Deleting all music on {name}"
            if dev == "garmin":
                j = common.start_isolated_job("wipe", dev, title, "summit.sync.garmin", "wipe")
            else:
                j = common.start_job("wipe", dev, title, fiio.wipe)
        elif action == "eject" and dev == "fiio":
            r = subprocess.run(["diskutil", "eject", fiio.mount_point()], capture_output=True, text=True)
            if r.returncode != 0:
                raise HTTPException(500, (r.stderr or r.stdout).strip())
            return {"ok": True}
        else:
            raise HTTPException(404, "unknown action")
    except RuntimeError as e:
        raise HTTPException(409 if str(e).startswith("Busy") else 500, str(e))
    return {"job": j.to_dict()}
