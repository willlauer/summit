"""Build ~/Applications/Summit.app: a launcher bundle that runs this checkout's venv (data, models and code stay here)."""

import plistlib
import shutil
import subprocess
import sys
import tempfile
from importlib.metadata import version
from pathlib import Path

from .config import ROOT

ICON = Path(__file__).parent / "assets/icon.png"
LOG = Path.home() / "Library/Logs/Summit.log"


def icns(png: Path, out: Path) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        iconset = Path(tmp) / "Summit.iconset"
        iconset.mkdir()
        for size in (16, 32, 128, 256, 512):
            for scale in (1, 2):
                name = f"icon_{size}x{size}{'@2x' if scale == 2 else ''}.png"
                px = str(size * scale)
                subprocess.run(["sips", "-z", px, px, str(png), "--out", str(iconset / name)], check=True, capture_output=True)
        subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(out)], check=True)


def bundle(dest: Path = Path.home() / "Applications") -> Path:
    app = dest / "Summit.app"
    if app.exists():
        shutil.rmtree(app)
    (app / "Contents/MacOS").mkdir(parents=True)
    (app / "Contents/Resources").mkdir()
    icns(ICON, app / "Contents/Resources/Summit.icns")
    plistlib.dump({
        "CFBundleName": "Summit", "CFBundleDisplayName": "Summit", "CFBundleIdentifier": "com.willlauer.summit",
        "CFBundleExecutable": "Summit", "CFBundleIconFile": "Summit", "CFBundlePackageType": "APPL",
        "CFBundleShortVersionString": version("summit"), "CFBundleVersion": version("summit"),
        "LSMinimumSystemVersion": "12.0", "NSHighResolutionCapable": True,
    }, (app / "Contents/Info.plist").open("wb"))
    python = Path(sys.prefix) / "bin/python"  # the venv's interpreter; exec keeps the bundle's pid so the Dock shows Summit
    launcher = app / "Contents/MacOS/Summit"
    launcher.write_text(f'#!/bin/sh\ncd "{ROOT}"\nexec "{python}" -m summit app >> "{LOG}" 2>&1\n')
    launcher.chmod(0o755)
    subprocess.run(["touch", str(app)])  # nudge Finder/Dock to pick up the new icon
    return app
