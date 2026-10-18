"""Native window: uvicorn in a background thread + a pywebview (WebKit) window, so AIFF/ALAC play natively."""

import threading
import time
import urllib.request

PORT = 8766  # MusicSync uses 8765


def run(browser: bool = False) -> None:
    import uvicorn

    from .server import app

    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=PORT, log_level="warning"))
    threading.Thread(target=server.run, daemon=True).start()
    url = f"http://127.0.0.1:{PORT}/"
    for _ in range(100):  # wait until the library is loaded and the server answers
        try:
            urllib.request.urlopen(url + "api/playlists", timeout=1)
            break
        except Exception:
            time.sleep(0.2)
    print(f"Summit at {url}")
    if browser:
        threading.Event().wait()
    import webview

    webview.create_window("Summit", url, width=1500, height=950, background_color="#07050b")
    webview.start()
    server.should_exit = True
