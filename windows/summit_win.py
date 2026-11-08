"""Summit for Windows: a native window (pywebview / WebView2) on the Summit server on your Mac, reached over Tailscale
like the phone. The Mac does all the work and the UI is the desktop app's own; this only remembers which server to open.

The pages notice they were opened from another machine (core.js: H.remote) and play the phone's AAC streams, since
WebView2 can't play AIFF/ALAC. Device sync stays on the Mac (the server refuses it from here)."""

import html
import json
import os
import sys
import urllib.request
from pathlib import Path

import webview
from webview.menu import Menu, MenuAction

HOME = Path(os.environ.get("APPDATA") or Path.home()) / "Summit"
CONFIG = HOME / "config.json"
try:
    from _version import VERSION  # written by build.ps1
except ImportError:
    VERSION = "dev"

window = None


def load() -> str:
    try:
        return json.loads(CONFIG.read_text()).get("server", "")
    except (OSError, ValueError):
        return ""


def save(server: str) -> None:
    HOME.mkdir(parents=True, exist_ok=True)
    CONFIG.write_text(json.dumps({"server": server}))


def normalize(url: str) -> str:
    url = url.strip()
    if url and "://" not in url:
        url = "https://" + url
    return url.rstrip("/") + "/" if url else ""


def check(server: str) -> str | None:
    """The server's version if Summit answers there."""
    try:
        with urllib.request.urlopen(server + "api/version", timeout=5) as r:
            return json.load(r).get("version")
    except Exception:
        return None


def connect_page(server: str = "", error: str = "") -> str:
    return f"""<!doctype html><meta charset="utf-8"><title>Summit</title>
<style>
  body {{ margin: 0; height: 100vh; display: grid; place-items: center; background: #08080d; color: #e6f6ff;
         font: 15px/1.5 'Segoe UI', system-ui, sans-serif; }}
  form {{ width: min(440px, 90vw); }}
  h1 {{ margin: 0 0 4px; font-size: 28px; letter-spacing: .02em; }}
  p {{ margin: 0 0 20px; color: #8b8fa4; }}
  input {{ box-sizing: border-box; width: 100%; padding: 10px 12px; border-radius: 8px; border: 1px solid #2f5677;
          background: #10233a; color: inherit; font: inherit; outline: none; }}
  input:focus {{ border-color: #5fd3f3; }}
  button {{ margin-top: 12px; padding: 9px 18px; border: 0; border-radius: 8px; background: #5fd3f3; color: #08080d;
           font: inherit; font-weight: 600; cursor: pointer; }}
  button:disabled {{ opacity: .5; cursor: default; }}
  .err {{ min-height: 1.5em; margin-top: 12px; color: #ff4d8d; }}
</style>
<form id="f">
  <h1>Summit</h1>
  <p>Connect to the Summit server on your Mac. It's the same address as the phone's, from
     <code>tailscale serve</code>: https://&lt;your-mac&gt;.&lt;tailnet&gt;.ts.net</p>
  <input id="u" placeholder="https://my-mac.tail1234.ts.net" value="{html.escape(server)}" autofocus spellcheck="false">
  <button id="b">Connect</button>
  <div class="err" id="e">{html.escape(error)}</div>
</form>
<script>
  f.onsubmit = async ev => {{
    ev.preventDefault(); b.disabled = true; e.textContent = '';
    e.textContent = await window.pywebview.api.connect(u.value);
    b.disabled = false;
  }};
</script>"""


class Api:
    """Exposed to the pages as window.pywebview.api."""

    def connect(self, url: str) -> str:
        server = normalize(url)
        if not server or not check(server):
            return f"Couldn't reach Summit at {server or '…'}. Is the Mac awake, and this PC on your tailnet?"
        save(server)
        window.load_url(server)
        return ""

    def app(self) -> dict:
        """For Settings › About."""
        return {"version": VERSION, "server": load()}


def change_server() -> None:
    window.load_html(connect_page(load()))


def reload() -> None:
    server = load()
    window.load_url(server) if server and check(server) else window.load_html(connect_page(server, "The server isn't answering."))


def main() -> None:
    global window
    server = load()
    up = bool(server) and check(server) is not None
    window = webview.create_window(
        "Summit", url=server if up else None,
        html=None if up else connect_page(server, "Couldn't reach the server. Is the Mac awake?" if server else ""),
        js_api=Api(), width=1500, height=950, min_size=(720, 500), background_color="#08080d",
        menu=[Menu("Summit", [MenuAction("Reload", reload), MenuAction("Change server…", change_server)])],
    )
    # Not private: keep localStorage (look, language, map settings) and the HTTP cache between launches.
    webview.start(private_mode=False, storage_path=str(HOME / "webview"), debug="--debug" in sys.argv)


if __name__ == "__main__":
    main()
