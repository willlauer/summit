"""Native window (pywebview / WebKit, so AIFF/ALAC play natively) on the local server: the background one if it is
running (summit service install), otherwise one started in a thread for as long as the window is open."""

import threading
import time
import urllib.request

PORT = 8766  # MusicSync uses 8765


def up(url: str) -> bool:
    try:
        urllib.request.urlopen(url + "api/playlists", timeout=1)
        return True
    except Exception:
        return False


def serve() -> None:
    """Server only, in the foreground (what the login service runs)."""
    import uvicorn

    from .server import app

    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning")


def run(browser: bool = False) -> None:
    url = f"http://127.0.0.1:{PORT}/"
    server = None
    if not up(url):  # no background server (summit service install): run one for as long as the window is open
        import uvicorn

        from .server import app

        server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=PORT, log_level="warning"))
        threading.Thread(target=server.run, daemon=True).start()
        for _ in range(100):  # wait until the library is loaded and the server answers
            if up(url):
                break
            time.sleep(0.2)
    print(f"Summit at {url}")
    if browser:
        threading.Event().wait()
    import webview

    dock()
    webview.create_window("Summit", url, width=1500, height=950, background_color="#08080d")
    webview.start()
    if server:
        server.should_exit = True


def dock() -> None:
    """Summit's name and icon in the Dock and menu bar, however it was launched (otherwise it shows as Python)."""
    try:
        from pathlib import Path

        from AppKit import NSApplication, NSImage
        from Foundation import NSBundle

        info = NSBundle.mainBundle().localizedInfoDictionary() or NSBundle.mainBundle().infoDictionary()
        info["CFBundleName"] = "Summit"
        icon = NSImage.alloc().initWithContentsOfFile_(str(Path(__file__).parent / "assets/icon.png"))
        NSApplication.sharedApplication().setApplicationIconImage_(icon)
    except Exception:
        pass
