"""Summit: a local vibe map of the music library.

    summit scan                 # index ~/Music/Music/Media.localized
    summit analyze [--limit N] [--sample]
    summit layout               # maps + family › sub-cluster organisation per space
    summit label                # auto-name clusters
    summit layout3d             # 3D positions for the map's 3D view (layout runs this too)
    summit update               # scan + analyze + layout + label: bring the map up to date with the library
    summit app [--browser]      # native window (or server only)
    summit serve                # server only, in the foreground (the phone app is at /m/)
    summit service install|uninstall|restart   # run the server in the background at login
    summit transcode            # convert lossless tracks for the phone now instead of on first play
    summit bundle               # build ~/Applications/Summit.app (launcher for this checkout)
    summit export               # rekordbox XML + m3u8 for all playlists
    summit status
"""

import argparse


def main() -> None:
    ap = argparse.ArgumentParser(prog="summit", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("scan")
    a = sub.add_parser("analyze")
    a.add_argument("--limit", type=int)
    a.add_argument("--sample", action="store_true", help="random order (for trial runs)")
    a.add_argument("--workers", type=int, default=5)
    sub.add_parser("layout")
    sub.add_parser("label")
    sub.add_parser("layout3d")
    sub.add_parser("update")
    p = sub.add_parser("app")
    p.add_argument("--browser", action="store_true", help="serve only; open http://127.0.0.1:8765 yourself")
    sub.add_parser("bundle")
    sub.add_parser("serve")
    sv = sub.add_parser("service")
    sv.add_argument("action", choices=["install", "uninstall", "restart"])
    sub.add_parser("transcode")
    sub.add_parser("export")
    sub.add_parser("status")
    args = ap.parse_args()
    if args.cmd in ("scan", "analyze", "layout", "layout3d", "label", "update"):
        import atexit

        from .pipeline import notify_server

        atexit.register(notify_server)  # also after Ctrl-C: every step saves as it goes

    if args.cmd == "scan":
        from .scan import scan

        scan()
    elif args.cmd == "analyze":
        from .analyze import analyze

        analyze(limit=args.limit, sample=args.sample, workers=args.workers)
    elif args.cmd == "layout":
        from .layout import layout

        layout()
    elif args.cmd == "layout3d":
        from .layout import layout3d

        layout3d()
    elif args.cmd == "label":
        from .label import label

        label()
    elif args.cmd == "update":
        from .pipeline import update

        update()
    elif args.cmd == "app":
        from .app import run

        run(browser=args.browser)
    elif args.cmd == "serve":
        from .app import serve

        serve()
    elif args.cmd == "service":
        from . import service

        print({"install": service.install, "uninstall": service.uninstall, "restart": service.restart}[args.action]())
    elif args.cmd == "transcode":
        from . import db
        from .media import transcode_all

        transcode_all(db.connect())
    elif args.cmd == "bundle":
        from .bundle import bundle

        print(bundle())
    elif args.cmd == "export":
        from . import db
        from .export import export_all

        print(export_all(db.connect()))
    elif args.cmd == "status":
        from . import db
        from .scan import pending

        con = db.connect()
        q = lambda s: con.execute(s).fetchone()[0]
        tracks, analyzed = q("SELECT COUNT(*) FROM tracks WHERE missing=0"), q("SELECT COUNT(*) FROM vectors WHERE kind='clap'")
        laid, playlists = q("SELECT COUNT(DISTINCT track_id) FROM layouts"), q("SELECT COUNT(*) FROM playlists")
        print(f"tracks {tracks} · analyzed {analyzed} · laid out {laid} · playlists {playlists}")
        p = pending(con)
        print(f"to do: {p['new']} new files · {p['unanalyzed']} unanalyzed · {p['unplaced']} not on the map · {p['failed']} failed"
              + (" (summit update)" if p["new"] or p["unanalyzed"] or p["unplaced"] else ""))
