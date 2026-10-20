"""Keep Summit's server running in the background (a login item), so the phone can reach it while the Mac is up.
The desktop window attaches to it instead of starting its own."""

import plistlib
import subprocess
import sys
from pathlib import Path

from .config import ROOT

LABEL = "com.willlauer.summit.server"
PLIST = Path.home() / f"Library/LaunchAgents/{LABEL}.plist"
LOG = Path.home() / "Library/Logs/Summit-server.log"


def install() -> str:
    PLIST.parent.mkdir(parents=True, exist_ok=True)
    uninstall(quiet=True)
    plistlib.dump({
        "Label": LABEL, "ProgramArguments": [str(Path(sys.prefix) / "bin/python"), "-m", "summit", "serve"],
        "WorkingDirectory": str(ROOT), "RunAtLoad": True, "KeepAlive": True, "ProcessType": "Interactive",
        "StandardOutPath": str(LOG), "StandardErrorPath": str(LOG),
        "EnvironmentVariables": {"PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"},  # ffmpeg lives in Homebrew
    }, PLIST.open("wb"))
    subprocess.run(["launchctl", "load", "-w", str(PLIST)], check=True)
    return f"installed {PLIST}; logs in {LOG}"


def uninstall(quiet: bool = False) -> str:
    if PLIST.exists():
        subprocess.run(["launchctl", "unload", "-w", str(PLIST)], capture_output=True)
        PLIST.unlink()
        return f"removed {PLIST}"
    return "" if quiet else "not installed"
