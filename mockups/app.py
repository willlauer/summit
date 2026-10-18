"""Run the UI mockups in a native WebKit window, with real audio streamed from the library.

    uv run mockups/app.py            # native window
    uv run mockups/app.py --browser  # server only, open http://127.0.0.1:8765 yourself
"""

import json
import mimetypes
import re
import sys
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).parent
PORT = 8765
LIB = json.loads((HERE / "library.json").read_text()) if (HERE / "library.json").exists() else []
AUDIO_TYPES = {"aiff": "audio/aiff", "aif": "audio/aiff", "m4a": "audio/mp4", "mp3": "audio/mpeg", "wav": "audio/wav", "flac": "audio/flac"}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(HERE), **kw)

    def log_message(self, *a):
        pass

    def do_GET(self):
        m = re.fullmatch(r"/audio/(\d+)", self.path)
        if m:
            return self.send_audio(int(m.group(1)))
        if self.path in ("/", ""):
            self.path = "/index.html"
        return super().do_GET()

    def send_audio(self, idx: int):
        # WebKit needs HTTP Range support to seek in (and sometimes to play) media.
        if not 0 <= idx < len(LIB):
            return self.send_error(404)
        t = LIB[idx]
        path = Path(t["path"])
        size = path.stat().st_size
        start, end = 0, size - 1
        rng = re.fullmatch(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        if rng:
            if rng.group(1):
                start = int(rng.group(1))
                end = int(rng.group(2)) if rng.group(2) else size - 1
            else:
                start = size - int(rng.group(2))
            end = min(end, size - 1)
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", AUDIO_TYPES.get(t["ext"]) or mimetypes.guess_type(path)[0] or "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        with path.open("rb") as f:
            f.seek(start)
            left = end - start + 1
            try:
                while left > 0:
                    chunk = f.read(min(1 << 16, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass


def main() -> None:
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{PORT}/"
    print(f"Serving {len(LIB)} tracks at {url}")
    if "--browser" in sys.argv:
        threading.Event().wait()
    import webview

    webview.create_window("Summit", url, width=1500, height=950, background_color="#0b0710")
    webview.start()


if __name__ == "__main__":
    main()
